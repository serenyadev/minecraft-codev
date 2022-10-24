package net.msrandom.minecraftcodev.core.repository

import com.google.common.io.Files
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.StartParameterResolutionOverride
import org.gradle.api.internal.artifacts.ivyservice.resolutionstrategy.DefaultExternalResourceCachePolicy
import org.gradle.api.internal.file.temp.TemporaryFileProvider
import org.gradle.cache.internal.ProducerGuard
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.hash.HashCode
import org.gradle.internal.hash.Hashing
import org.gradle.internal.impldep.org.apache.commons.io.IOUtils
import org.gradle.internal.impldep.org.apache.commons.lang.StringUtils
import org.gradle.internal.resource.ExternalResource
import org.gradle.internal.resource.ExternalResourceName
import org.gradle.internal.resource.ExternalResourceRepository
import org.gradle.internal.resource.cached.CachedExternalResource
import org.gradle.internal.resource.cached.DefaultCachedExternalResource
import org.gradle.internal.resource.local.FileResourceRepository
import org.gradle.internal.resource.local.LocallyAvailableExternalResource
import org.gradle.internal.resource.local.LocallyAvailableResource
import org.gradle.internal.resource.local.LocallyAvailableResourceCandidates
import org.gradle.internal.resource.metadata.ExternalResourceMetaData
import org.gradle.internal.resource.metadata.ExternalResourceMetaDataCompare
import org.gradle.internal.serialize.BaseSerializerFactory
import org.gradle.internal.serialize.MapSerializer
import org.gradle.util.internal.BuildCommencedTimeProvider
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.UncheckedIOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories

open class DefaultCacheAwareExternalResourceAccessor @Inject constructor(
    private val repository: ExternalResourceRepository,
    cacheProvider: CodevCacheProvider,
    private val timeProvider: BuildCommencedTimeProvider,
    private val temporaryFileProvider: TemporaryFileProvider,
    startParameterResolutionOverride: StartParameterResolutionOverride,
    private val producerGuard: ProducerGuard<ExternalResourceName>,
    private val fileResourceRepository: FileResourceRepository,
    private val checksumService: ChecksumService
) {
    private val externalResourceCachePolicy = startParameterResolutionOverride.overrideExternalResourceCachePolicy(DefaultExternalResourceCachePolicy())

    private val cachedExternalResourceIndex = run {
        val cacheManager = cacheProvider.manager("minecraft")
        cacheManager.getMetadataCache(Path("resource-at-url"), emptyMap<String, CachedExternalResource>()) {
            MapSerializer(
                BaseSerializerFactory.STRING_SERIALIZER,
                CachedExternalResourceSerializer(cacheManager.rootPath)
            )
        }.asFile
    }

    fun getResource(
        location: ExternalResourceName,
        sha1: String?,
        destination: Path,
        additionalCandidates: LocallyAvailableResourceCandidates?,
        shouldRefresh: (File, Duration) -> Boolean
    ): LocallyAvailableExternalResource? {
        return producerGuard.guardByKey(location) {
            LOGGER.debug("Constructing external resource: {}", location)
            val index: Map<String, CachedExternalResource> = cachedExternalResourceIndex.value
            val cached = index[location.toString()]

            // If we have no caching options, just get the thing directly
            if (cached == null && (additionalCandidates == null || additionalCandidates.isNone)) {
                return@guardByKey copyToCache(location, destination, repository.withProgressLogging().resource(location), index)
            }

            // We might be able to use a cached/locally available version
            if (cached != null && (!shouldRefresh(cached.cachedFile!!, Duration.ofMillis(timeProvider.currentTime - cached.cachedAt)) || !externalResourceCachePolicy.mustRefreshExternalResource(getAgeMillis(timeProvider, cached)))) {
                return@guardByKey fileResourceRepository.resource(cached.cachedFile, location.uri, cached.externalResourceMetaData)
            }

            // Get the metadata first to see if it's there
            val remoteMetaData = repository.resource(location, true).metaData ?: return@guardByKey null

            // Is the cached version still current?
            if (cached != null) {
                val isUnchanged = ExternalResourceMetaDataCompare.isDefinitelyUnchanged(cached.externalResourceMetaData) { remoteMetaData }
                if (isUnchanged) {
                    LOGGER.info("Cached resource {} is up-to-date (lastModified: {}).", location, cached.externalLastModified)
                    // Update the cache entry in the index: this resets the age of the cached entry to zero
                    cachedExternalResourceIndex.update(index + (location.toString() to DefaultCachedExternalResource(cached.cachedFile, Instant.now().toEpochMilli(), cached.externalResourceMetaData)))
                    return@guardByKey fileResourceRepository.resource(cached.cachedFile, location.uri, cached.externalResourceMetaData)
                }
            }

            // Either no cached, or it's changed. See if we can find something local with the same checksum
            if (additionalCandidates != null && !additionalCandidates.isNone) {
                // The “remote” may have already given us the checksum
                val remoteChecksum = sha1?.let(HashCode::fromString) ?: remoteMetaData.sha1 ?: getResourceSha1(location)

                if (remoteChecksum != null) {
                    val local = additionalCandidates.findByHashValue(remoteChecksum)
                    if (local != null) {
                        LOGGER.info("Found locally available resource with matching checksum: [{}, {}]", location, local.file)
                        // TODO - should iterate over each candidate until we successfully copy into the cache
                        val resource = try {
                            copyCandidateToCache(location, destination, remoteMetaData, remoteChecksum, local, index)
                        } catch (e: IOException) {
                            throw UncheckedIOException(e)
                        }
                        if (resource != null) {
                            return@guardByKey resource
                        }
                    }
                }
            }
            copyToCache(location, destination, repository.withProgressLogging().resource(location, true), index)
        }
    }

    private fun getResourceSha1(location: ExternalResourceName): HashCode? {
        return try {
            val sha1Location = location.append(".sha1")
            val resource = repository.resource(sha1Location, true)
            val result = resource.withContentIfPresent { inputStream: InputStream? ->
                var sha = IOUtils.toString(inputStream, StandardCharsets.US_ASCII)
                // Servers may return SHA-1 with leading zeros stripped
                sha = StringUtils.leftPad(sha, Hashing.sha1().hexDigits, '0')
                HashCode.fromString(sha)
            }
            result?.result
        } catch (e: Exception) {
            LOGGER.debug(String.format("Failed to download SHA1 for resource '%s'.", location), e)
            null
        }
    }

    @Throws(IOException::class)
    private fun copyCandidateToCache(
        source: ExternalResourceName,
        destination: Path,
        remoteMetaData: ExternalResourceMetaData,
        remoteChecksum: HashCode,
        local: LocallyAvailableResource,
        index: Map<String, CachedExternalResource>
    ): LocallyAvailableExternalResource? {
        val temp = temporaryFileProvider.createTemporaryFile("gradle_download", "bin")
        return try {
            Files.copy(local.file, temp)
            val localChecksum = checksumService.sha1(temp)
            if (localChecksum != remoteChecksum) {
                null
            } else moveIntoCache(source, temp, destination, remoteMetaData, index)
        } finally {
            temp.delete()
        }
    }

    private fun copyToCache(source: ExternalResourceName, destination: Path, resource: ExternalResource, index: Map<String, CachedExternalResource>): LocallyAvailableExternalResource? {
        // Download to temporary location
        val downloadAction = DownloadAction(source)
        resource.withContentIfPresent(downloadAction)
        return if (downloadAction.metaData == null) {
            null
        } else try {
            moveIntoCache(source, downloadAction.destination, destination, downloadAction.metaData, index)
        } finally {
            downloadAction.destination.delete()
        }

        // Move into cache
    }

    private fun moveIntoCache(
        source: ExternalResourceName,
        destination: File?,
        output: Path,
        metaData: ExternalResourceMetaData?,
        index: Map<String, CachedExternalResource>
    ): LocallyAvailableExternalResource {
        val fileInFileStore = output.toFile()
        output.parent?.createDirectories()
        destination?.toPath()?.copyTo(output, StandardCopyOption.REPLACE_EXISTING)
        cachedExternalResourceIndex.update(index + (source.toString() to DefaultCachedExternalResource(fileInFileStore, Instant.now().toEpochMilli(), metaData)))
        return fileResourceRepository.resource(fileInFileStore, source.uri, metaData)
    }

    private fun getAgeMillis(timeProvider: BuildCommencedTimeProvider, cached: CachedExternalResource): Long {
        return timeProvider.currentTime - cached.cachedAt
    }

    private inner class DownloadAction internal constructor(private val source: ExternalResourceName) : ExternalResource.ContentAndMetadataAction<Any?> {
        lateinit var destination: File
        var metaData: ExternalResourceMetaData? = null
        @Throws(IOException::class)
        override fun execute(inputStream: InputStream, metaData: ExternalResourceMetaData): Any? {
            destination = temporaryFileProvider.createTemporaryFile("gradle_download", "bin")
            this.metaData = metaData
            LOGGER.info("Downloading {} to {}", source, destination)
            destination.toPath().parent?.createDirectories()
            java.nio.file.Files.copy(inputStream, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return null
        }
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(DefaultCacheAwareExternalResourceAccessor::class.java)
    }
}