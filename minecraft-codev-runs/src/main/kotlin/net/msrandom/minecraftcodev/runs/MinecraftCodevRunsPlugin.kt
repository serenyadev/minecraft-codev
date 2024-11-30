package net.msrandom.minecraftcodev.runs

import net.msrandom.minecraftcodev.core.utils.applyPlugin
import net.msrandom.minecraftcodev.core.utils.createSourceSetElements
import net.msrandom.minecraftcodev.core.utils.getCacheDirectoryProvider
import net.msrandom.minecraftcodev.runs.task.DownloadAssets
import net.msrandom.minecraftcodev.runs.task.ExtractNatives
import org.apache.commons.lang3.StringUtils
import org.gradle.api.Plugin
import org.gradle.api.plugins.ApplicationPlugin
import org.gradle.api.plugins.PluginAware
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSet
import org.gradle.configurationcache.extensions.serviceOf
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.util.internal.GUtil

class MinecraftCodevRunsPlugin<T : PluginAware> : Plugin<T> {
    override fun apply(target: T) =
        applyPlugin(target) {
            // Log4j configs
            val cache = getCacheDirectoryProvider(this)
            // val logging: Path = cache.resolve("logging")

            fun addSourceElements(
                extractNativesTaskName: String,
                downloadAssetsTaskName: String,
            ) {
                tasks.register(extractNativesTaskName, ExtractNatives::class.java)
                tasks.register(downloadAssetsTaskName, DownloadAssets::class.java)
            }

            createSourceSetElements {
                addSourceElements(
                    it.extractNativesTaskName,
                    it.downloadAssetsTaskName,
                )
            }

            val runs = project.extensions.create(RunsContainer::class.java, "minecraftRuns", RunsContainerImpl::class.java, cache)

            runs.extensions.create("defaults", RunConfigurationDefaultsContainer::class.java)

            project.integrateIdeaRuns()

            runs.all { builder ->
                fun taskName(builder: MinecraftRunConfigurationBuilder) =
                    ApplicationPlugin.TASK_RUN_NAME + GUtil.toCamelCase(builder.name.replace(':', '-'))

                tasks.register(taskName(builder), JavaExec::class.java) { javaExec ->
                    val configuration = builder.build()

                    javaExec.environment = configuration.environment.keySet().get().associateWith {
                        object {
                            override fun toString() = configuration.environment.getting(it).get()
                        }
                    }

                    javaExec.javaLauncher.set(project.serviceOf<JavaToolchainService>().launcherFor {
                        it.languageVersion.set(configuration.jvmVersion.map(JavaLanguageVersion::of))
                    })

                    javaExec.argumentProviders.add(configuration.arguments::get)

                    javaExec.jvmArgumentProviders.add(configuration.jvmArguments::get)

                    javaExec.workingDir(configuration.executableDirectory)
                    javaExec.mainClass.set(configuration.mainClass)

                    javaExec.classpath = files(configuration.sourceSet.map(SourceSet::getRuntimeClasspath))

                    javaExec.group = ApplicationPlugin.APPLICATION_GROUP

                    javaExec.dependsOn(configuration.beforeRun)

                    javaExec.dependsOn(
                        configuration.dependsOn.map {
                            it.map(::taskName)
                        },
                    )

                    javaExec.dependsOn(configuration.sourceSet.map(SourceSet::getClassesTaskName))
                }
            }
        }
}
