package org.carlspring.strongbox.providers.repository;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;

import javax.inject.Inject;

import org.carlspring.strongbox.artifact.ArtifactNotFoundException;
import org.carlspring.strongbox.data.criteria.Paginator;
import org.carlspring.strongbox.domain.Artifact;
import org.carlspring.strongbox.providers.io.AbstractRepositoryProvider;
import org.carlspring.strongbox.providers.io.RepositoryFiles;
import org.carlspring.strongbox.providers.io.RepositoryPath;
import org.carlspring.strongbox.providers.io.RepositoryPathResolver;
import org.carlspring.strongbox.providers.io.RootRepositoryPath;
import org.carlspring.strongbox.repositories.ArtifactIdGroupRepository;
import org.carlspring.strongbox.storage.Storage;
import org.carlspring.strongbox.storage.repository.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * @author carlspring
 */
@Component
public class HostedRepositoryProvider extends AbstractRepositoryProvider
{

    private static final Logger logger = LoggerFactory.getLogger(HostedRepositoryProvider.class);

    private static final String ALIAS = "hosted";

    @Inject
    private ArtifactIdGroupRepository artifactIdGroupRepository;
    
    @Inject
    private RepositoryPathResolver repositoryPathResolver;
    
    @Override
    public String getAlias()
    {
        return ALIAS;
    }

    protected InputStream getInputStreamInternal(RepositoryPath repositoryPath) throws IOException
    {
        try
        {
            return Files.newInputStream(repositoryPath);
        }
        catch (ArtifactNotFoundException e) 
        {
            logger.debug("The path [{}] does not exist!\n*\t[{}]", repositoryPath, e.getMessage());

            return null;
        }
        catch (IOException ex)
        {
            logger.error("Failed to decorate InputStream for [{}]", repositoryPath, ex);
            
            throw ex;
        }
    }

    @Override
    public OutputStream getOutputStreamInternal(RepositoryPath repositoryPath)
            throws IOException
    {
        return Files.newOutputStream(repositoryPath);
    }

    @Override
    public List<Path> search(String storageId,
                             String repositoryId,
                             RepositorySearchRequest predicate,
                             Paginator paginator)
    {
        List<Path> result = new LinkedList<Path>();

        Storage storage = configurationManager.getConfiguration().getStorage(storageId);
        Repository repository = storage.getRepository(repositoryId);
        
        RootRepositoryPath rootRepositoryPath = repositoryPathResolver.resolve(repository);
        List<Artifact> searchResult = artifactIdGroupRepository.findArtifacts(storageId, repositoryId, predicate.getArtifactId(),
                                                                              predicate.getCoordinateValues(),
                                                                              paginator.getSkip(), paginator.getLimit());
        for (Artifact artifactEntry : searchResult)
        {
            
            try
            {
                result.add(rootRepositoryPath.resolve(artifactEntry));
            }
            catch (Exception e)
            {
                logger.error("Failed to resolve Artifact [{}]",
                             artifactEntry.getArtifactCoordinates(), e);
                continue;
            }
        }
        
        return result;
    }

    @Override
    public Long count(String storageId,
                      String repositoryId,
                      RepositorySearchRequest predicate)
    {
        return artifactIdGroupRepository.countArtifacts(storageId, repositoryId, predicate.getArtifactId(),
                                                        predicate.getCoordinateValues());
    }

    @Override
    protected RepositoryPath fetchPath(RepositoryPath repositoryPath) 
           throws IOException
    {
        logger.debug(" -> Checking local cache for {} ...", repositoryPath);
        if (artifactNotExists(repositoryPath))
        {
            logger.debug("The artifact {} was not found in the local cache", repositoryPath);

            return null;
        }
        
        logger.debug("The artifact {} was found in the local cache", repositoryPath);
        return repositoryPath;
    }

    private boolean artifactNotExists(RepositoryPath repositoryPath) throws IOException
    {
        return !RepositoryFiles.artifactExists(repositoryPath);
    }

}
