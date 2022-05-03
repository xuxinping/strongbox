package org.carlspring.strongbox.services;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.commons.io.IOUtils;
import org.carlspring.strongbox.artifact.coordinates.ArtifactCoordinates;
import org.carlspring.strongbox.configuration.Configuration;
import org.carlspring.strongbox.configuration.ConfigurationManager;
import org.carlspring.strongbox.domain.Artifact;
import org.carlspring.strongbox.event.artifact.ArtifactEventListenerRegistry;
import org.carlspring.strongbox.io.LayoutOutputStream;
import org.carlspring.strongbox.io.StreamUtils;
import org.carlspring.strongbox.providers.ProviderImplementationException;
import org.carlspring.strongbox.providers.io.RepositoryFiles;
import org.carlspring.strongbox.providers.io.RepositoryPath;
import org.carlspring.strongbox.providers.io.RepositoryPathResolver;
import org.carlspring.strongbox.providers.io.RepositoryStreamSupport.RepositoryOutputStream;
import org.carlspring.strongbox.providers.layout.LayoutFileSystemProvider;
import org.carlspring.strongbox.providers.layout.LayoutProviderRegistry;
import org.carlspring.strongbox.repositories.ArtifactRepository;
import org.carlspring.strongbox.storage.ArtifactStorageException;
import org.carlspring.strongbox.storage.Storage;
import org.carlspring.strongbox.storage.checksum.ArtifactChecksum;
import org.carlspring.strongbox.storage.checksum.ChecksumCacheManager;
import org.carlspring.strongbox.storage.repository.Repository;
import org.carlspring.strongbox.storage.validation.ArtifactCoordinatesValidator;
import org.carlspring.strongbox.storage.validation.artifact.ArtifactCoordinatesValidationException;
import org.carlspring.strongbox.storage.validation.artifact.ArtifactCoordinatesValidatorRegistry;
import org.carlspring.strongbox.storage.validation.artifact.version.VersionValidationException;
import org.carlspring.strongbox.storage.validation.resource.ArtifactOperationsValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.FileSystemUtils;

/**
 * @author mtodorov
 */
@Component
public class ArtifactManagementService
{
    private static final Logger logger = LoggerFactory.getLogger(ArtifactManagementService.class);

    @Inject
    protected ArtifactOperationsValidator artifactOperationsValidator;

    @Inject
    protected ArtifactCoordinatesValidatorRegistry artifactCoordinatesValidatorRegistry;

    @Inject
    protected ConfigurationManager configurationManager;

    @Inject
    protected ArtifactRepository artifactEntityRepository;

    @Inject
    protected LayoutProviderRegistry layoutProviderRegistry;

    @Inject
    protected ArtifactResolutionService artifactResolutionService;

    @Inject
    protected ChecksumCacheManager checksumCacheManager;

    @Inject
    protected ArtifactEventListenerRegistry artifactEventListenerRegistry;

    @Inject
    protected RepositoryPathResolver repositoryPathResolver;

    public long validateAndStore(RepositoryPath repositoryPath,
                                 InputStream is)
        throws IOException,
        ProviderImplementationException,
        ArtifactCoordinatesValidationException
    {
        performRepositoryAcceptanceValidation(repositoryPath);
        return doStore(repositoryPath, is);
    }

    public long store(RepositoryPath repositoryPath,
                      InputStream is)
        throws IOException
    {
        return doStore(repositoryPath, is);
    }

    private long doStore(RepositoryPath repositoryPath,
                         InputStream is)
            throws IOException
    {
        long result;
        try (final RepositoryOutputStream aos = artifactResolutionService.getOutputStream(repositoryPath))
        {
            result = writeArtifact(repositoryPath, is, aos);
            logger.debug("Stored [{}] bytes for [{}].", result, repositoryPath);
            aos.flush();
        }
        catch (IOException e)
        {
           throw e;
        }
        catch (Exception e)
        {
            throw new ArtifactStorageException(e);
        }

        return result;
    }

    private long writeArtifact(RepositoryPath repositoryPath,
                               InputStream is,
                               OutputStream os)
            throws IOException
    {
        LayoutOutputStream aos = StreamUtils.findSource(LayoutOutputStream.class, os);

        Repository repository = repositoryPath.getRepository();

        Boolean checksumAttribute = RepositoryFiles.isChecksum(repositoryPath);

        // If we have no digests, then we have a checksum to store.
        if (Boolean.TRUE.equals(checksumAttribute))
        {
            aos.setCacheOutputStream(new ByteArrayOutputStream());
        }

        if (repository.isHostedRepository())
        {
            artifactEventListenerRegistry.dispatchArtifactUploadingEvent(repositoryPath);
        }

        long totalAmountOfBytes = IOUtils.copy(is, os);

        URI repositoryPathId = repositoryPath.toUri();
        Map<String, String> digestMap = aos.getDigestMap();
        if (Boolean.FALSE.equals(checksumAttribute) && !digestMap.isEmpty())
        {
            // Store artifact digests in cache if we have them.
            addChecksumsToCacheManager(digestMap, repositoryPathId);

            writeChecksums(repositoryPath, digestMap);
        }

        if (Boolean.TRUE.equals(checksumAttribute))
        {
            byte[] checksumValue = ((ByteArrayOutputStream) aos.getCacheOutputStream()).toByteArray();
            if (checksumValue != null && checksumValue.length > 0)
            {
                // Validate checksum with artifact digest cache.
                validateUploadedChecksumAgainstCache(checksumValue, repositoryPathId);
            }
        }

        return totalAmountOfBytes;
    }

    private void writeChecksums(RepositoryPath repositoryPath,
                                Map<String, String> digestMap)
    {
        LayoutFileSystemProvider provider = (LayoutFileSystemProvider) repositoryPath.getFileSystem().provider();

        digestMap.entrySet()
                 .stream()
                 .forEach(entry -> {
                     final RepositoryPath checksumPath = provider.getChecksumPath(repositoryPath, entry.getKey());
                     try
                     {
                         Files.write(checksumPath, entry.getValue().getBytes(StandardCharsets.UTF_8));
                     }
                     catch (IOException ex)
                     {
                         logger.error(ex.getMessage(), ex);
                     }
                 });
    }

    private void validateUploadedChecksumAgainstCache(byte[] checksum,
                                                      URI artifactPathId)
    {
        logger.debug("Received checksum: {}", new String(checksum, StandardCharsets.UTF_8));

        String artifactPath = artifactPathId.toString();
        String artifactBasePath = artifactPath.substring(0, artifactPath.lastIndexOf('.'));
        String checksumExtension = artifactPath.substring(artifactPath.lastIndexOf('.') + 1, artifactPath.length());

        if (!matchesChecksum(checksum, artifactBasePath, checksumExtension))
        {
            logger.error("The checksum for {} [{}] is invalid!",
                         artifactPath,
                         new String(checksum, StandardCharsets.UTF_8));
        }

        checksumCacheManager.removeArtifactChecksum(artifactBasePath, checksumExtension);
    }

    private boolean matchesChecksum(byte[] pChecksum,
                                    String artifactBasePath,
                                    String checksumExtension)
    {
        String checksum = new String(pChecksum, StandardCharsets.UTF_8);
        ArtifactChecksum artifactChecksum = checksumCacheManager.getArtifactChecksum(artifactBasePath);

        if (artifactChecksum == null)
        {
            return false;
        }

        Map<Boolean, Set<String>> matchingMap = artifactChecksum.getChecksums()
                                                                .entrySet()
                                                                .stream()
                                                                .collect(Collectors.groupingBy(e -> e.getValue()
                                                                                                     .equals(checksum),
                                                                                               Collectors.mapping(
                                                                                                       e -> e.getKey(),
                                                                                                       Collectors.toSet())));

        Set<String> matched = matchingMap.get(Boolean.TRUE);
        Set<String> unmatched = matchingMap.get(Boolean.FALSE);

        logger.debug("Artifact checksum matchings: artifact-[{}]; ext-[{}]; matched-[{}];" +
                     " unmatched-[{}]; checksum-[{}]",
                     artifactBasePath,
                     checksumExtension,
                     matched,
                     unmatched,
                     checksum);

        return matched != null && !matched.isEmpty();
    }

    private void addChecksumsToCacheManager(Map<String, String> digestMap,
                                            URI artifactPath)
    {
        digestMap.entrySet()
                 .stream()
                 .forEach(e -> checksumCacheManager.addArtifactChecksum(artifactPath.toString(), e.getKey(), e.getValue()));
    }

    private boolean performRepositoryAcceptanceValidation(RepositoryPath path)
            throws IOException, ProviderImplementationException, ArtifactCoordinatesValidationException
    {
        logger.info("Validate artifact with path [{}]", path);

        Repository repository = path.getFileSystem().getRepository();

        artifactOperationsValidator.validate(path);

        if (!RepositoryFiles.isArtifact(path))
        {
            return true;
        }

        ArtifactCoordinates coordinates = RepositoryFiles.readCoordinates(path);
        logger.info("Validate artifact with coordinates [{}]", coordinates);

        try
        {
            for (String validatorKey : repository.getArtifactCoordinateValidators())
            {
                ArtifactCoordinatesValidator validator = artifactCoordinatesValidatorRegistry.getProvider(
                        validatorKey);
                if (validator.supports(repository))
                {
                    validator.validate(repository, coordinates);
                }
            }
        }
        catch (VersionValidationException e)
        {
            throw new ArtifactStorageException(e);
        }

        artifactOperationsValidator.checkAllowsRedeployment(repository, coordinates);
        artifactOperationsValidator.checkAllowsDeployment(repository);

        return true;
    }

    protected Storage getStorage(String storageId)
    {
        return getConfiguration().getStorages().get(storageId);
    }

    protected Configuration getConfiguration()
    {
        return configurationManager.getConfiguration();
    }

    @Transactional
    public void delete(RepositoryPath repositoryPath,
                       boolean force)
            throws IOException
    {
        artifactOperationsValidator.validate(repositoryPath);

        final Repository repository = repositoryPath.getRepository();

        artifactOperationsValidator.checkAllowsDeletion(repository);

        Optional<Artifact> artifactEntry = Optional.ofNullable(repositoryPath.getArtifactEntry());
        if (!Files.isDirectory(repositoryPath) && RepositoryFiles.isArtifact(repositoryPath) && !artifactEntry.isPresent())
        {
            throw new IOException(String.format("Corresponding [%s] record not found for path [%s]",
                                                Artifact.class.getSimpleName(), repositoryPath));
        }

        try
        {
            RepositoryFiles.delete(repositoryPath, force);
        }
        catch (IOException e)
        {
            throw new ArtifactStorageException(e.getMessage(), e);
        }
    }

    public void copy(RepositoryPath srcPath, RepositoryPath destPath)
            throws IOException
    {
        artifactOperationsValidator.validate(srcPath);

        if (Files.isDirectory(srcPath))
        {
            FileSystemUtils.copyRecursively(srcPath, destPath);
        }
        else
        {
            Files.copy(srcPath, destPath);
        }
    }

}
