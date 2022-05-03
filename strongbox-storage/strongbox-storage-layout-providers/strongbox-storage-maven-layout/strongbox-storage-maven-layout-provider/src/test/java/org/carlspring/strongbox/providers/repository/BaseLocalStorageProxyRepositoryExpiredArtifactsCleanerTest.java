package org.carlspring.strongbox.providers.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.commons.lang.time.DateUtils;
import org.carlspring.strongbox.configuration.Configuration;
import org.carlspring.strongbox.domain.Artifact;
import org.carlspring.strongbox.providers.io.RepositoryPath;
import org.carlspring.strongbox.providers.io.RepositoryPathResolver;
import org.carlspring.strongbox.providers.repository.proxied.LocalStorageProxyRepositoryExpiredArtifactsCleaner;
import org.carlspring.strongbox.repositories.ArtifactRepository;
import org.carlspring.strongbox.services.ConfigurationManagementService;
import org.carlspring.strongbox.storage.repository.remote.heartbeat.RemoteRepositoryAlivenessService;
import org.springframework.aop.TargetSource;

/**
 * @author Przemyslaw Fusik
 * @author Pablo Tirado
 */
abstract class BaseLocalStorageProxyRepositoryExpiredArtifactsCleanerTest
{
    protected static final String STORAGE_ID = "storage-common-proxies";

    @Inject
    protected ProxyRepositoryProvider proxyRepositoryProvider;

    @Inject
    protected ArtifactRepository artifactEntityRepository;

    @Inject
    protected LocalStorageProxyRepositoryExpiredArtifactsCleaner localStorageProxyRepositoryExpiredArtifactsCleaner;

    @Inject
    @Named("remoteRepositoryAlivenessCacheManagerTargetSource") 
    private TargetSource remoteRepositoryAlivenessCacheManagerTargetSource;

    @Inject
    protected RepositoryPathResolver repositoryPathResolver;

    @Inject
    protected ConfigurationManagementService configurationManagementService;

    protected abstract String getRepositoryId();

    protected abstract String getPath();

    protected String getGroupId()
    {
        return "org.carlspring.maven";
    }

    protected String getArtifactId()
    {
        return "maven-commons";
    }

    protected abstract String getVersion();

    protected Artifact downloadAndSaveArtifactEntry()
            throws Exception
    {
        Artifact artifact = artifactEntityRepository.findOneArtifact(STORAGE_ID,
                                                                     getRepositoryId(),
                                                                     getPath());
        Optional<Artifact> artifactEntryOptional = Optional.ofNullable(artifact);
        assertThat(artifactEntryOptional).isEqualTo(Optional.empty());

        RepositoryPath repositoryPath = proxyRepositoryProvider.fetchPath(repositoryPathResolver.resolve(STORAGE_ID,
                                                                                                         getRepositoryId(),
                                                                                                         getPath()));
        try (final InputStream ignored = proxyRepositoryProvider.getInputStream(repositoryPath))
        {
            assertThat(ignored).as("Failed to resolve " + repositoryPath + "!").isNotNull();
        }

        artifactEntryOptional = Optional.ofNullable(artifactEntityRepository.findOneArtifact(STORAGE_ID,
                                                                                             getRepositoryId(),
                                                                                             getPath()));
        Artifact artifactEntry = artifactEntryOptional.orElse(null);
        assertThat(artifactEntry).isNotNull();
        assertThat(artifactEntry.getLastUpdated()).isNotNull();
        assertThat(artifactEntry.getLastUsed()).isNotNull();
        assertThat(artifactEntry.getSizeInBytes()).isNotNull();
        assertThat(artifactEntry.getSizeInBytes()).isGreaterThan(0L);

        artifactEntry.setLastUsed(artifactEntry.getLastUsed().minusDays(10));

        return artifactEntityRepository.save(artifactEntry);
    }

    protected Configuration getConfiguration()
    {
        return configurationManagementService.getConfiguration();
    }
    
    protected RemoteRepositoryAlivenessService getRemoteRepositoryAlivenessMock() throws Exception {
        return (RemoteRepositoryAlivenessService) remoteRepositoryAlivenessCacheManagerTargetSource.getTarget(); 
    }

}
