package org.carlspring.strongbox.repository;

import org.carlspring.strongbox.artifact.ArtifactTag;
import org.carlspring.strongbox.artifact.coordinates.NugetArtifactCoordinates;
import org.carlspring.strongbox.configuration.Configuration;
import org.carlspring.strongbox.configuration.ConfigurationManager;
import org.carlspring.strongbox.data.criteria.Paginator;
import org.carlspring.strongbox.domain.Artifact;
import org.carlspring.strongbox.domain.ArtifactEntity;
import org.carlspring.strongbox.domain.ArtifactTagEntity;
import org.carlspring.strongbox.nuget.NugetSearchRequest;
import org.carlspring.strongbox.providers.repository.RepositorySearchRequest;
import org.carlspring.strongbox.providers.repository.event.RemoteRepositorySearchEvent;
import org.carlspring.strongbox.repositories.ArtifactIdGroupRepository;
import org.carlspring.strongbox.service.ProxyRepositoryConnectionPoolConfigurationService;
import org.carlspring.strongbox.services.ArtifactIdGroupService;
import org.carlspring.strongbox.services.ArtifactTagService;
import org.carlspring.strongbox.storage.Storage;
import org.carlspring.strongbox.storage.metadata.nuget.rss.PackageEntry;
import org.carlspring.strongbox.storage.metadata.nuget.rss.PackageFeed;
import org.carlspring.strongbox.storage.repository.Repository;
import org.carlspring.strongbox.storage.repository.remote.RemoteRepository;
import org.carlspring.strongbox.storage.validation.artifact.version.GenericReleaseVersionValidator;
import org.carlspring.strongbox.storage.validation.artifact.version.GenericSnapshotVersionValidator;
import org.carlspring.strongbox.storage.validation.deployment.RedeploymentValidator;
import org.carlspring.strongbox.util.LocalDateTimeInstance;
import org.carlspring.strongbox.yaml.configuration.repository.NugetRepositoryConfiguration;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.WebTarget;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 
 * @author carlspring
 * @author Sergey Bespalov
 */
@Component
public class NugetRepositoryFeatures
        implements RepositoryFeatures
{

    private static final int REMOTE_FEED_PAGE_SIZE = 1000;

    private static final Logger logger = LoggerFactory.getLogger(NugetRepositoryFeatures.class);

    @Inject
    private ConfigurationManager configurationManager;

    @Inject
    private ArtifactTagService artifactTagService;

    @Inject
    private ProxyRepositoryConnectionPoolConfigurationService proxyRepositoryConnectionPoolConfigurationService;

    @Inject
    private ArtifactIdGroupRepository artifactIdGroupRepository;

    @Inject
    private RedeploymentValidator redeploymentValidator;

    @Inject
    private GenericReleaseVersionValidator genericReleaseVersionValidator;

    @Inject
    private GenericSnapshotVersionValidator genericSnapshotVersionValidator;

    @Inject
    private ArtifactIdGroupService artifactIdGroupService;
    
    private Set<String> defaultMavenArtifactCoordinateValidators;


    @PostConstruct
    public void init()
    {
        defaultMavenArtifactCoordinateValidators = new LinkedHashSet<>(Arrays.asList(redeploymentValidator.getAlias(),
                                                                                     genericReleaseVersionValidator.getAlias(),
                                                                                     genericSnapshotVersionValidator.getAlias()));
    }

    public void downloadRemoteFeed(String storageId,
                                   String repositoryId)
            throws IOException
    {
        downloadRemoteFeed(storageId, repositoryId, new NugetSearchRequest());
    }

    public void downloadRemoteFeed(String storageId,
                                   String repositoryId,
                                   NugetSearchRequest nugetSearchRequest)
            throws IOException
    {
        Storage storage = getConfiguration().getStorage(storageId);
        Repository repository = storage.getRepository(repositoryId);

        Optional<NugetRepositoryConfiguration> repositoryConfiguration = Optional.ofNullable((NugetRepositoryConfiguration) repository.getRepositoryConfiguration());
        Integer remoteFeedPageSize = repositoryConfiguration.map(c -> c.getRemoteFeedPageSize())
                                                            .orElse(REMOTE_FEED_PAGE_SIZE);

        for (int i = 0; true; i++)
        {
            if (!downloadRemoteFeed(storageId,
                                    repositoryId,
                                    nugetSearchRequest,
                                    i * remoteFeedPageSize,
                                    remoteFeedPageSize))
            {
                break;
            }
        }
    }

    public boolean downloadRemoteFeed(String storageId,
                                      String repositoryId,
                                      NugetSearchRequest nugetSearchRequest,
                                      long skip,
                                      int top)
    {
        Storage storage = getConfiguration().getStorage(storageId);
        Repository repository = storage.getRepository(repositoryId);

        RemoteRepository remoteRepository = repository.getRemoteRepository();
        if (remoteRepository == null)
        {
            return false;
        }
        String remoteRepositoryUrl = remoteRepository.getUrl();

        Paginator paginator = new Paginator();
        paginator.setLimit(top);
        paginator.setSkip(skip);

        PackageFeed packageFeed;
        Client restClient = proxyRepositoryConnectionPoolConfigurationService.getRestClient();
        try
        {
            logger.debug("Downloading remote feed for [{}].", remoteRepositoryUrl);

            WebTarget service = restClient.target(remoteRepository.getUrl());
            packageFeed = queryParams(service.path("Search()"), nugetSearchRequest, paginator).request()
                                                                                              .buildGet()
                                                                                              .invoke(PackageFeed.class);

            logger.debug("Downloaded remote feed for [{}], size [{}].",
                         remoteRepository.getUrl(),
                         Optional.of(packageFeed).map(f -> f.getEntries().size()).orElse(0));

        }
        catch (Exception e)
        {
            logger.error("Failed to fetch Nuget remote feed [{}]", remoteRepositoryUrl, e);
            return false;
        }
        finally
        {
            restClient.close();
        }

        if (packageFeed == null || packageFeed.getEntries() == null || packageFeed.getEntries().size() == 0)
        {
            return false;
        }

        parseFeed(repository, packageFeed);

        return true;
    }

    private void parseFeed(Repository repository,
                           PackageFeed packageFeed)
    {
        String repositoryId = repository.getId();
        String storageId = repository.getStorage().getId();

        ArtifactTag lastVersionTag = artifactTagService.findOneOrCreate(ArtifactTagEntity.LAST_VERSION);

        Set<Artifact> artifactToSaveSet = new HashSet<>();
        for (PackageEntry packageEntry : packageFeed.getEntries())
        {
            String packageId = packageEntry.getProperties().getId();
            packageId = packageId == null ? packageEntry.getTitle() : packageId;
            String packageVersion = packageEntry.getProperties().getVersion().toString();

            NugetArtifactCoordinates c = new NugetArtifactCoordinates(packageId, packageVersion, "nupkg");

            LocalDateTime now = LocalDateTimeInstance.now();

            ArtifactEntity remoteArtifactEntry = new ArtifactEntity(storageId, repositoryId, c);
            remoteArtifactEntry.setStorageId(storageId);
            remoteArtifactEntry.setRepositoryId(repositoryId);
            remoteArtifactEntry.setArtifactCoordinates(c);
            remoteArtifactEntry.setLastUsed(now);
            remoteArtifactEntry.setLastUpdated(now);
            remoteArtifactEntry.setDownloadCount(0);
            remoteArtifactEntry.setArtifactFileExists(Boolean.FALSE);

            remoteArtifactEntry.setSizeInBytes(packageEntry.getProperties().getPackageSize());

            if (Boolean.TRUE.equals(packageEntry.getProperties().getIsLatestVersion()))
            {
                remoteArtifactEntry.getTagSet().add(lastVersionTag);
            }

            artifactToSaveSet.add(remoteArtifactEntry);
        }
        
        artifactIdGroupService.saveArtifacts(repository, artifactToSaveSet);
    }


    protected Configuration getConfiguration()
    {
        return configurationManager.getConfiguration();
    }

    @Component
    @Scope(scopeName = "request", proxyMode = ScopedProxyMode.TARGET_CLASS)
    public class RepositorySearchEventListener
    {

        private NugetSearchRequest nugetSearchRequest;

        public NugetSearchRequest getNugetSearchRequest()
        {
            return nugetSearchRequest;
        }

        public void setNugetSearchRequest(NugetSearchRequest nugetSearchRequest)
        {
            this.nugetSearchRequest = nugetSearchRequest;
        }

        @EventListener
        public void handle(RemoteRepositorySearchEvent event)
        {
            if (nugetSearchRequest == null)
            {
                return;
            }
            
            Storage storage = getConfiguration().getStorage(event.getStorageId());
            Repository repository = storage.getRepository(event.getRepositoryId());
            RemoteRepository remoteRepository = repository.getRemoteRepository();
            if (remoteRepository == null)
            {
                return;
            }

            RepositorySearchRequest predicate = event.getPredicate();
            String repositoryId = event.getRepositoryId();
            String storageId = event.getStorageId();
            Long packageCount = artifactIdGroupRepository.countArtifacts(Collections.singleton(storageId + ":" + repositoryId),
                                                                         predicate.getArtifactId(),
                                                                         predicate.getCoordinateValues());

            logger.debug("Remote repository [{}] cached package count is [{}]", repository.getId(), packageCount);

            Client restClient = proxyRepositoryConnectionPoolConfigurationService.getRestClient();
            PackageFeed feed;
            try
            {
                WebTarget service = restClient.target(remoteRepository.getUrl());

                Long remotePackageCount = Long.valueOf(queryParams(service.path("Search()/$count"),
                                                                   nugetSearchRequest, new Paginator()).request()
                                                                                                       .buildGet()
                                                                                                       .invoke(String.class));
                logger.debug("Remote repository [{}] remote package count is [{}]", repository.getId(), remotePackageCount);

                if (Long.valueOf(remotePackageCount).compareTo(packageCount) == 0)
                {
                    logger.debug("No need to download remote feed, there was no changes in remote repository [{}] against local cache.",
                                 remoteRepository.getUrl());
                    return;
                }

                logger.debug("Downloading remote feed for [{}].", remoteRepository.getUrl());

                feed = queryParams(service.path("Search()"), nugetSearchRequest, event.getPaginator()).request()
                                                                                                      .buildGet()
                                                                                                      .invoke(PackageFeed.class);

                logger.debug("Downloaded remote feed for [{}], size [{}].",
                             remoteRepository.getUrl(),
                             Optional.of(feed).map(f -> f.getEntries().size()).orElse(0));

            }
            catch (Exception e)
            {
                logger.error("Failed to fetch Nuget remote feed [{}]", remoteRepository.getUrl(), e);
                return;
            } 
            finally
            {
                restClient.close();
            }

            parseFeed(repository, feed);
        }

    }

    private WebTarget queryParams(WebTarget path,
                                  NugetSearchRequest nugetSearchRequest,
                                  Paginator paginator)
    {
        if (nugetSearchRequest.getFilter() != null && !nugetSearchRequest.getFilter().trim().isEmpty())
        {
            path = path.queryParam("$filter", nugetSearchRequest.getFilter().trim());
        }
        if (nugetSearchRequest.getSearchTerm() != null && !nugetSearchRequest.getSearchTerm().trim().isEmpty())
        {
            path = path.queryParam("searchTerm", nugetSearchRequest.getSearchTerm().trim());
        }
        if (nugetSearchRequest.getTargetFramework() != null
                && !nugetSearchRequest.getTargetFramework().trim().isEmpty())
        {
            path = path.queryParam("targetFramework", nugetSearchRequest.getTargetFramework().trim());
        }

        if (paginator.getSkip() != null && paginator.getSkip() > 0)
        {
            path = path.queryParam("$skip", paginator.getSkip());
        }
        if (paginator.getLimit() != null && paginator.getLimit() > 0)
        {
            path = path.queryParam("$top", paginator.getLimit());
        }

        //https://chocolatey.org/api/v2/$metadata 
        // includePrerelease is required parameter to invoke Search() method
        path = path.queryParam("includePrerelease", Boolean.TRUE.equals(nugetSearchRequest.getIncludePreRelease()));

        return path;
    }

    @Override
    public Set<String> getDefaultArtifactCoordinateValidators()
    {
        return defaultMavenArtifactCoordinateValidators;
    }

}
