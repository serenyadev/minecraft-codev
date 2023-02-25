package net.msrandom.minecraftcodev.forge

import kotlinx.serialization.json.decodeFromStream
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import net.msrandom.minecraftcodev.core.dependency.registerCustomDependency
import net.msrandom.minecraftcodev.core.utils.applyPlugin
import net.msrandom.minecraftcodev.core.utils.createSourceSetConfigurations
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import net.msrandom.minecraftcodev.forge.dependency.PatchedMinecraftDependencyFactory
import net.msrandom.minecraftcodev.forge.dependency.PatchedMinecraftDependencyMetadata
import net.msrandom.minecraftcodev.forge.dependency.PatchedMinecraftIvyDependencyDescriptorFactory
import net.msrandom.minecraftcodev.forge.mappings.setupForgeRemapperIntegration
import net.msrandom.minecraftcodev.forge.resolve.PatchedMinecraftComponentResolvers
import org.gradle.api.Plugin
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Attribute
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.JvmEcosystemPlugin
import org.gradle.api.plugins.PluginAware
import java.io.File
import java.nio.file.FileSystem
import kotlin.io.path.exists
import kotlin.io.path.inputStream

open class MinecraftCodevForgePlugin<T : PluginAware> : Plugin<T> {
    private fun applyGradle(gradle: Gradle) = gradle.registerCustomDependency(
        "patched",
        PatchedMinecraftIvyDependencyDescriptorFactory::class.java,
        PatchedMinecraftDependencyFactory::class.java,
        PatchedMinecraftComponentResolvers::class.java,
        PatchedMinecraftDependencyMetadata::class.java
    )

    override fun apply(target: T) = applyPlugin(target, ::applyGradle) {
        createSourceSetConfigurations(PATCHES_CONFIGURATION)

        dependencies.attributesSchema { schema ->
            schema.attribute(FORGE_TRANSFORMED_ATTRIBUTE)
        }

        plugins.withType(JvmEcosystemPlugin::class.java) {
            dependencies.artifactTypes.getByName(ArtifactTypeDefinition.JAR_TYPE) {
                it.attributes.attribute(FORGE_TRANSFORMED_ATTRIBUTE, false)
            }
        }

        configurations.all { configuration ->
            configuration.attributes {
                it.attribute(FORGE_TRANSFORMED_ATTRIBUTE, true)
            }
        }

        @Suppress("UnstableApiUsage")
        dependencies.registerTransform(ForgeJarTransformer::class.java) {
            it.from.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE).attribute(FORGE_TRANSFORMED_ATTRIBUTE, false)
            it.to.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE).attribute(FORGE_TRANSFORMED_ATTRIBUTE, true)
        }

        extensions.getByType(MinecraftCodevExtension::class.java).extensions.create("patched", PatchedMinecraftCodevExtension::class.java)
        setupForgeRemapperIntegration()
        setupForgeRunsIntegration()
    }

    companion object {
        const val SRG_MAPPINGS_NAMESPACE = "srg"
        const val PATCHES_CONFIGURATION = "patches"

        @JvmField
        val FORGE_TRANSFORMED_ATTRIBUTE: Attribute<Boolean> = Attribute.of("net.msrandom.minecraftcodev.forgeTransformed", Boolean::class.javaObjectType)

        internal fun userdevConfig(file: File, action: FileSystem.(config: UserdevConfig) -> Unit) = zipFileSystem(file.toPath()).use { fs ->
            val configPath = fs.getPath("config.json")
            if (configPath.exists()) {
                fs.action(configPath.inputStream().use(json::decodeFromStream))
                true
            } else {
                false
            }
        }
    }
}
