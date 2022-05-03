package org.carlspring.strongbox.providers.repository;

import org.carlspring.strongbox.artifact.AsyncArtifactEntryHandler;
import org.carlspring.strongbox.domain.Artifact;
import org.carlspring.strongbox.event.artifact.ArtifactEventTypeEnum;
import org.carlspring.strongbox.providers.io.RepositoryPath;
import org.carlspring.strongbox.util.LocalDateTimeInstance;

import java.io.IOException;

import org.springframework.stereotype.Component;

@Component
public class ArtifactDownloadingEventHandler extends AsyncArtifactEntryHandler
{

    public ArtifactDownloadingEventHandler()
    {
        super(ArtifactEventTypeEnum.EVENT_ARTIFACT_FILE_DOWNLOADING);
    }

    @Override
    protected Artifact handleEvent(RepositoryPath repositoryPath) throws IOException
    {
        Artifact artifactEntry = repositoryPath.getArtifactEntry();
        
        artifactEntry.setDownloadCount(artifactEntry.getDownloadCount() + 1);
        artifactEntry.setLastUsed(LocalDateTimeInstance.now());

        return artifactEntry;
    }

}
