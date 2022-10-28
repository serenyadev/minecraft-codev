package net.msrandom.minecraftcodev.gradle

import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.capabilities.CapabilitiesMetadata
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.DisplayName
import org.gradle.internal.classloader.VisitableURLClassLoader
import org.gradle.internal.component.model.*
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

object CodevGradleLinkageLoader {
    private val classLoader = (ComponentResolveMetadata::class.java.classLoader as VisitableURLClassLoader).also {
        it.addURL(javaClass.protectionDomain.codeSource.location)
    }

    private val customComponentResolveMetadata = loadClass<ComponentResolveMetadata>("CustomComponentResolveMetadata")
    private val delegatingComponentResolveMetadata = loadClass<ComponentResolveMetadata>("DelegatingComponentResolveMetadata")
    private val customConfigurationMetadata = loadClass<ConfigurationMetadata>("CustomConfigurationMetadata")
    private val delegatingConfigurationMetadata = loadClass<ConfigurationMetadata>("DelegatingConfigurationMetadata")

    private val getDelegateHandle by lazy {
        MethodHandles.publicLookup().findVirtual(delegatingComponentResolveMetadata, "getDelegate", MethodType.methodType(ComponentResolveMetadata::class.java))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> loadClass(name: String): Class<out T> =
        Class.forName("net.msrandom.minecraftcodev.gradle.$name", true, classLoader) as Class<out T>

    fun ComponentResolveMetadata(
        attributes: ImmutableAttributes,
        id: ModuleComponentIdentifier,
        moduleVersionId: ModuleVersionIdentifier,
        variants: List<ConfigurationMetadata>,
        isChanging: Boolean,
        status: String,
        statusScheme: List<String>,
        objects: ObjectFactory
    ): ComponentResolveMetadata = objects.newInstance(customComponentResolveMetadata, attributes, id, moduleVersionId, variants, isChanging, status, statusScheme, ImmutableModuleSources.of())

    fun ComponentResolveMetadata.copy(
        id: ComponentIdentifier,
        configuration: ConfigurationMetadata.() -> ConfigurationMetadata,
        objects: ObjectFactory
    ): ComponentResolveMetadata = objects.newInstance(delegatingComponentResolveMetadata, this, id, configuration)

    fun ConfigurationMetadata(
        name: String,
        componentId: ModuleComponentIdentifier,
        dependencies: List<DependencyMetadata>,
        artifacts: List<ComponentArtifactMetadata>,
        attributes: ImmutableAttributes,
        capabilities: CapabilitiesMetadata,
        hierarchy: Set<String>,
        objects: ObjectFactory
    ): ConfigurationMetadata = objects.newInstance(customConfigurationMetadata, name, componentId, dependencies, artifacts, attributes, capabilities, hierarchy)

    fun ConfigurationMetadata.copy(
        describable: (DisplayName) -> DisplayName,
        attributes: ImmutableAttributes,
        dependency: (DependencyMetadata) -> DependencyMetadata,
        artifact: (ComponentArtifactMetadata) -> ComponentArtifactMetadata,
        objects: ObjectFactory
    ): ConfigurationMetadata = objects.newInstance(delegatingConfigurationMetadata, this, describable, attributes, dependency, artifact)

    fun getDelegate(metadata: ComponentResolveMetadata) = metadata
        .takeIf(delegatingComponentResolveMetadata::isInstance)
        ?.let { getDelegateHandle(it) as ComponentResolveMetadata }
        ?: throw UnsupportedOperationException("$metadata does not wrap anything. Can not unwrap.")
}
