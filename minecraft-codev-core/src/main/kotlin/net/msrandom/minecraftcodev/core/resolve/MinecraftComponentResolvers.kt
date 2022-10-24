package net.msrandom.minecraftcodev.core.resolve

import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.dependency.MinecraftDependencyMetadataWrapper
import net.msrandom.minecraftcodev.core.repository.MinecraftRepository
import net.msrandom.minecraftcodev.core.repository.MinecraftRepositoryImpl
import org.gradle.api.artifacts.ModuleIdentifier
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.attributes.Attribute
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.RepositoriesSupplier
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.modulecache.FileStoreAndIndexProvider
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactSet
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec
import org.gradle.api.internal.artifacts.repositories.ResolutionAwareRepository
import org.gradle.api.internal.artifacts.type.ArtifactTypeRegistry
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.attributes.ImmutableAttributesFactory
import org.gradle.api.internal.model.NamedObjectInstantiator
import org.gradle.api.model.ObjectFactory
import org.gradle.cache.scopes.GlobalScopedCache
import org.gradle.internal.component.external.model.MetadataSourcedComponentArtifacts
import org.gradle.internal.component.model.ComponentArtifactMetadata
import org.gradle.internal.component.model.ComponentOverrideMetadata
import org.gradle.internal.component.model.ComponentResolveMetadata
import org.gradle.internal.component.model.ConfigurationMetadata
import org.gradle.internal.hash.HashCode
import org.gradle.internal.model.CalculatedValueContainerFactory
import org.gradle.internal.resolve.resolver.ArtifactResolver
import org.gradle.internal.resolve.resolver.ComponentMetaDataResolver
import org.gradle.internal.resolve.resolver.DependencyToComponentIdResolver
import org.gradle.internal.resolve.resolver.OriginArtifactSelector
import org.gradle.internal.resolve.result.BuildableComponentResolveResult
import org.gradle.internal.snapshot.impl.CoercingStringValueSnapshot
import javax.inject.Inject

open class MinecraftComponentResolvers @Inject constructor(
    private val cachePolicy: CachePolicy,
    private val objects: ObjectFactory,
    private val attributesFactory: ImmutableAttributesFactory,
    private val instantiator: NamedObjectInstantiator,
    private val globalScopedCache: GlobalScopedCache,
    private val calculatedValueContainerFactory: CalculatedValueContainerFactory,
    private val fileStoreAndIndexProvider: FileStoreAndIndexProvider,
    cacheProvider: CodevCacheProvider,

    repositoriesSupplier: RepositoriesSupplier
) : ComponentResolvers, ComponentMetaDataResolver, OriginArtifactSelector {
    private val repositories = repositoriesSupplier.get().asSequence()
        .filterIsInstance<MinecraftRepository>()
        .filterIsInstance<ResolutionAwareRepository>()
        .map(ResolutionAwareRepository::createResolver)
        .filterIsInstance<MinecraftRepositoryImpl.Resolver>()
        .toList()

    private val componentIdResolver = objects.newInstance(MinecraftDependencyToComponentIdResolver::class.java, repositories)
    private val artifactResolver = objects.newInstance(MinecraftArtifactResolver::class.java, repositories)

    private val cacheManager = cacheProvider.manager("minecraft")

    override fun getComponentIdResolver(): DependencyToComponentIdResolver = componentIdResolver
    override fun getComponentResolver() = this
    override fun getArtifactSelector() = this
    override fun getArtifactResolver(): ArtifactResolver = artifactResolver

    override fun resolve(identifier: ComponentIdentifier, componentOverrideMetadata: ComponentOverrideMetadata, result: BuildableComponentResolveResult) {
        if (identifier is MinecraftComponentIdentifier) {
            if (identifier.version.endsWith("-SNAPSHOT")) {
                val realVersion = identifier.version.substring(0, identifier.version.length - "-SNAPSHOT".length)

            } else if (UNIQUE_VERSION_ID matches identifier.version) {

            }

            for (repository in repositories) {
                if (result.hasResult()) break

                val metadataGenerator = objects.newInstance(MinecraftMetadataGenerator::class.java, cacheManager)

                metadataGenerator.resolveMetadata(
                    repository,
                    emptyList(),
                    "",
                    repository.transport.resourceAccessor,
                    identifier,
                    componentOverrideMetadata,
                    result,
                    MappingsNamespace.OBF,
                    ::MinecraftDependencyMetadataWrapper
                )
            }
        }
    }

    override fun isFetchingMetadataCheap(identifier: ComponentIdentifier) = false

    override fun resolveArtifacts(
        component: ComponentResolveMetadata, configuration: ConfigurationMetadata, artifactTypeRegistry: ArtifactTypeRegistry, exclusions: ExcludeSpec, overriddenAttributes: ImmutableAttributes
    ): ArtifactSet? {
        val componentIdentifier = component.id
        return if (componentIdentifier is MinecraftComponentIdentifier) {
            // Direct server downloads are not allowed, as the common dependency should be used for that instead
            val valid = if (componentIdentifier.module.startsWith(PREFIX) && !componentIdentifier.module.endsWith(SERVER_DOWNLOAD)) {
                if (componentIdentifier.module == CLIENT_MODULE) {
                    true
                } else {
                    var exists = false

                    for (repository in repositories) {
                        val manifest = MinecraftMetadataGenerator.getVersionManifest(
                            componentIdentifier, repository.url, globalScopedCache, cachePolicy, repository.transport.resourceAccessor, fileStoreAndIndexProvider, null
                        )

                        if (manifest != null) {
                            if (componentIdentifier.module == COMMON_MODULE) {
                                if (SERVER_DOWNLOAD in manifest.downloads) {
                                    exists = true
                                    break
                                }
                            } else {
                                val fixedName = componentIdentifier.module.asMinecraftDownload()

                                if (fixedName != SERVER_DOWNLOAD && fixedName in manifest.downloads) {
                                    exists = true
                                    break
                                }
                            }
                        }
                    }

                    exists
                }
            } else {
                false
            }

            if (valid) {
                // TODO use artifact caches
                MetadataSourcedComponentArtifacts().getArtifactsFor(
                    component, configuration, artifactResolver, hashMapOf(), artifactTypeRegistry, exclusions, overriddenAttributes, calculatedValueContainerFactory
                )
            } else {
                null
            }
        } else {
            null
        }
    }

    companion object {
        const val GROUP = "net.minecraft"
        const val PREFIX = ""

        const val COMMON_MODULE = "${PREFIX}common"
        const val CLIENT_MODULE = "${PREFIX}client"
        const val CLIENT_DOWNLOAD = "client"
        const val SERVER_DOWNLOAD = "server"

        private val UNIQUE_VERSION_ID = Regex(".+-(\\d{8}\\.\\d{6}-\\d+)")

        private val getArtifactsMethod = ConfigurationMetadata::class.java.getMethod("getArtifacts")

        init {
            getArtifactsMethod.isAccessible = true
        }

        @Suppress("UNCHECKED_CAST")
        internal fun getArtifacts(configurationMetadata: ConfigurationMetadata) = getArtifactsMethod(configurationMetadata) as List<ComponentArtifactMetadata>

        fun ImmutableAttributes.addInt(attributesFactory: ImmutableAttributesFactory, attribute: Attribute<Int>, value: Int) = attributesFactory.concat(this, attribute, value)

        fun <T> ImmutableAttributes.addNamed(attributesFactory: ImmutableAttributesFactory, instantiator: NamedObjectInstantiator, attribute: Attribute<T>, value: T) =
            attributesFactory.concat(this, Attribute.of(attribute.name, String::class.java), CoercingStringValueSnapshot(value.toString(), instantiator))

        fun String.asMinecraftDownload() = takeIf { startsWith(PREFIX) && !contains('_') }?.removePrefix(PREFIX)?.replace('-', '_')

        fun ComponentArtifactMetadata.hash(): HashCode = HashCode.fromBytes(name.hashCode().let {
            byteArrayOf((it and 0xFF).toByte(), (it shl 8 and 0xFF).toByte(), (it shl 16 and 0xFF).toByte(), (it shl 24 and 0xFF).toByte())
        })
    }
}

class MinecraftComponentIdentifier(module: String, private val version: String) : ModuleComponentIdentifier {
    private val moduleIdentifier = DefaultModuleIdentifier.newId(MinecraftComponentResolvers.GROUP, module)

    override fun getDisplayName() = "Minecraft $module $version"
    override fun getGroup(): String = moduleIdentifier.group
    override fun getModule(): String = moduleIdentifier.name
    override fun getVersion() = version
    override fun getModuleIdentifier(): ModuleIdentifier = moduleIdentifier
}
