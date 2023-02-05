package net.msrandom.minecraftcodev.remapper

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin
import net.msrandom.minecraftcodev.core.utils.applyPlugin
import net.msrandom.minecraftcodev.core.utils.createSourceSetConfigurations
import net.msrandom.minecraftcodev.core.dependency.registerCustomDependency
import net.msrandom.minecraftcodev.remapper.dependency.RemappedDependencyFactory
import net.msrandom.minecraftcodev.remapper.dependency.RemappedDependencyMetadata
import net.msrandom.minecraftcodev.remapper.dependency.RemappedIvyDependencyDescriptorFactory
import net.msrandom.minecraftcodev.remapper.resolve.RemappedComponentResolvers
import org.gradle.api.Plugin
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.PluginAware

class MinecraftCodevRemapperPlugin<T : PluginAware> : Plugin<T> {
    private fun applyGradle(gradle: Gradle) = gradle.registerCustomDependency(
        "remapped",
        RemappedIvyDependencyDescriptorFactory::class.java,
        RemappedDependencyFactory::class.java,
        RemappedComponentResolvers::class.java,
        RemappedDependencyMetadata::class.java
    )

    override fun apply(target: T) {
        target.plugins.apply(MinecraftCodevPlugin::class.java)

        applyPlugin(target, ::applyGradle) {
            val remapper = extensions.getByType(MinecraftCodevExtension::class.java).extensions.create("remapper", RemapperExtension::class.java)

            createSourceSetConfigurations(MAPPINGS_CONFIGURATION)
        }
    }

    companion object {
        const val NAMED_MAPPINGS_NAMESPACE = "named"
        const val MAPPINGS_CONFIGURATION = "mappings"
    }
}
