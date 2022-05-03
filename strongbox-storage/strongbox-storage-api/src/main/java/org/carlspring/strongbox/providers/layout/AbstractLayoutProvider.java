package org.carlspring.strongbox.providers.layout;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;

import org.apache.commons.codec.digest.MessageDigestAlgorithms;
import org.carlspring.strongbox.artifact.archive.ArchiveListingFunction;
import org.carlspring.strongbox.artifact.archive.Bzip2ArchiveListingFunction;
import org.carlspring.strongbox.artifact.archive.CompositeArchiveListingFunction;
import org.carlspring.strongbox.artifact.archive.TarArchiveListingFunction;
import org.carlspring.strongbox.artifact.archive.TarGzArchiveListingFunction;
import org.carlspring.strongbox.artifact.archive.ZipArchiveListingFunction;
import org.carlspring.strongbox.artifact.coordinates.ArtifactCoordinates;
import org.carlspring.strongbox.configuration.ConfigurationManager;
import org.carlspring.strongbox.domain.ArtifactGroup;
import org.carlspring.strongbox.domain.ArtifactIdGroup;
import org.carlspring.strongbox.domain.LayoutArtifactCoordinatesEntity;
import org.carlspring.strongbox.providers.storage.StorageProviderRegistry;
import org.carlspring.strongbox.providers.io.LayoutFileSystem;
import org.carlspring.strongbox.providers.io.RepositoryFileAttributeType;
import org.carlspring.strongbox.providers.io.RepositoryFiles;
import org.carlspring.strongbox.providers.io.RepositoryPath;
import org.carlspring.strongbox.repositories.ArtifactIdGroupRepository;
import org.carlspring.strongbox.storage.Storage;
import org.carlspring.strongbox.storage.repository.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriComponentsBuilder;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

/**
 * @author mtodorov
 */
public abstract class AbstractLayoutProvider<T extends LayoutArtifactCoordinatesEntity>
        implements LayoutProvider<T>
{

    private static final Logger logger = LoggerFactory.getLogger(AbstractLayoutProvider.class);

    private static final ArchiveListingFunction ARCHIVE_LISTING_FUNCTION = new CompositeArchiveListingFunction(
            ImmutableSet.of(
                ZipArchiveListingFunction.INSTANCE,
                TarGzArchiveListingFunction.INSTANCE,
                TarArchiveListingFunction.INSTANCE,
                Bzip2ArchiveListingFunction.INSTANCE
            )
    );

    @Inject
    private ConfigurationManager configurationManager;

    @Inject
    private ArtifactIdGroupRepository artifactIdGroupRepository;

    @Inject
    protected StorageProviderRegistry storageProviderRegistry;

    public abstract Set<String> getDefaultArtifactCoordinateValidators();

    protected abstract boolean isArtifactMetadata(RepositoryPath repositoryPath);

    protected abstract T getArtifactCoordinates(RepositoryPath repositoryPath) throws IOException;

    protected Set<String> getDigestAlgorithmSet()
    {
        return Stream.of(MessageDigestAlgorithms.MD5, MessageDigestAlgorithms.SHA_1)
                     .collect(Collectors.toSet());
    }

    public boolean isChecksum(RepositoryPath repositoryPath)
    {
        return isChecksum(repositoryPath.getFileName().toString());
    }

    protected boolean isChecksum(String fileName)
    {
        for (String e : getDigestAlgorithmSet())
        {
            if (fileName.toString().endsWith("." + e.replaceAll("-", "").toLowerCase()))
            {
                return true;
            }
        }

        return false;
    }


    protected Map<RepositoryFileAttributeType, Object> getRepositoryFileAttributes(RepositoryPath repositoryPath,
                                                                                   RepositoryFileAttributeType... attributeTypes)
        throws IOException
    {
        if (attributeTypes == null || attributeTypes.length == 0)
        {
            return Collections.emptyMap();
        }

        Map<RepositoryFileAttributeType, Object> result = new HashMap<>();
        for (RepositoryFileAttributeType repositoryFileAttributeType : attributeTypes)
        {
            Object value;
            switch (repositoryFileAttributeType)
            {
            default:
                Map<RepositoryFileAttributeType, Object> attributesLocal;
                value = null;

                break;
            case CHECKSUM:
                value = isChecksum(repositoryPath);

                break;
            case TEMP:
                value = repositoryPath.isAbsolute()
                        && repositoryPath.startsWith(repositoryPath.getFileSystem()
                                                                   .getRootDirectory()
                                                                   .resolve(LayoutFileSystem.TEMP));

                break;
            case TRASH:
                value = repositoryPath.isAbsolute()
                        && repositoryPath.startsWith(repositoryPath.getFileSystem()
                                                                   .getRootDirectory()
                                                                   .resolve(LayoutFileSystem.TRASH));

                break;
            case METADATA:
                value = isArtifactMetadata(repositoryPath);

                break;
            case ARTIFACT:
                attributesLocal = getRepositoryFileAttributes(repositoryPath,
                                                              RepositoryFileAttributeType.CHECKSUM);

                boolean isChecksum = Boolean.TRUE.equals(attributesLocal.get(RepositoryFileAttributeType.CHECKSUM));
                boolean isDirectory = Files.isDirectory(repositoryPath);

                value = !isChecksum && !isDirectory;

                break;
            case COORDINATES:
                attributesLocal = getRepositoryFileAttributes(repositoryPath,
                                                              RepositoryFileAttributeType.ARTIFACT);

                boolean isArtifact = Boolean.TRUE.equals(attributesLocal.get(RepositoryFileAttributeType.ARTIFACT));
                if (!isArtifact) {
                    value = null;
                    break;
                }
                
                T artifactCoordinates = getArtifactCoordinates(repositoryPath);
                artifactCoordinates.setUuid(artifactCoordinates.buildPath());
                
                value = artifactCoordinates;
                break;
            case RESOURCE_URL:
                value = resolveResource(repositoryPath);

                break;
            case ARTIFACT_PATH:
                value = RepositoryFiles.relativizePath(repositoryPath);

                break;

            case STORAGE_ID:
                value = repositoryPath.getRepository().getStorage().getId();

                break;

            case REPOSITORY_ID:
                value = repositoryPath.getRepository().getId();

                break;
            case EXPIRED:
                value = false;

                break;

            }
            if (value != null)
            {
                result.put(repositoryFileAttributeType, value);
            }
        }

        return result;
    }

    public URL resolveResource(RepositoryPath repositoryPath)
            throws IOException
    {
        URI baseUri = configurationManager.getBaseUri();

        Repository repository = repositoryPath.getRepository();
        Storage storage = repository.getStorage();
        URI artifactResource = RepositoryFiles.resolveResource(repositoryPath);

        return UriComponentsBuilder.fromUri(baseUri)
                                   .pathSegment("storages", storage.getId(), repository.getId(), "/")
                                   .build()
                                   .toUri()
                                   .resolve(artifactResource)
                                   .toURL();
    }

    @Override
    public Set<String> listArchiveFilenames(final RepositoryPath repositoryPath)
    {
        if (ARCHIVE_LISTING_FUNCTION.supports(repositoryPath))
        {
            try
            {
                return ARCHIVE_LISTING_FUNCTION.listFilenames(repositoryPath);
            }
            catch (IOException e)
            {
                logger.warn("Unable to list filenames in archive path {} using {}",
                            repositoryPath, ARCHIVE_LISTING_FUNCTION, e);
            }
        }
        return Collections.emptySet();
    }

    @Override
    public Set<ArtifactGroup> getArtifactGroups(RepositoryPath path)
            throws IOException
    {
        if (!RepositoryFiles.isArtifact(path))
        {
            return Collections.emptySet();
        }
        
        Repository repository = path.getFileSystem().getRepository();
        Storage storage = repository.getStorage();
        ArtifactCoordinates c = RepositoryFiles.readCoordinates(path);
        Optional<ArtifactIdGroup> artifactIdGroup = artifactIdGroupRepository.findAllArtifactsInGroup(storage.getId(),
                                                                                                      repository.getId(),
                                                                                                      c.getId());
        
        return artifactIdGroup.map(ArtifactGroup.class::cast).map(Collections::singleton).orElse(Collections.emptySet());
    }
}
