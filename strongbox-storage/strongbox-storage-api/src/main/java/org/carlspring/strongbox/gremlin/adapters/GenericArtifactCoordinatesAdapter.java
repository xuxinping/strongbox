package org.carlspring.strongbox.gremlin.adapters;

import static org.apache.tinkerpop.gremlin.structure.VertexProperty.Cardinality.single;
import static org.carlspring.strongbox.gremlin.dsl.EntityTraversalUtils.extractPropertyList;
import static org.carlspring.strongbox.gremlin.dsl.EntityTraversalUtils.extractObject;

import java.util.Map;
import java.util.Map.Entry;

import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.carlspring.strongbox.artifact.coordinates.GenericArtifactCoordinates;
import org.carlspring.strongbox.db.schema.Vertices;
import org.carlspring.strongbox.domain.GenericArtifactCoordinatesEntity;
import org.carlspring.strongbox.gremlin.dsl.EntityTraversal;
import org.carlspring.strongbox.gremlin.dsl.__;
import org.springframework.stereotype.Component;

/**
 * @author sbespalov
 */
@Component
public class GenericArtifactCoordinatesAdapter
        implements VertexEntityTraversalAdapter<GenericArtifactCoordinates>, ArtifactCoodrinatesNodeAdapter
{

    @Override
    public String label()
    {
        return Vertices.GENERIC_ARTIFACT_COORDINATES;
    }

    @Override
    public Class<? extends GenericArtifactCoordinates> entityClass()
    {
        return GenericArtifactCoordinates.class;
    }

    @Override
    public EntityTraversal<Vertex, GenericArtifactCoordinates> fold()
    {
        return __.<Vertex, Object>project("id", "uuid", "version", "coordinates")
                 .by(__.id())
                 .by(__.enrichPropertyValue("uuid"))
                 .by(__.enrichPropertyValue("version"))
                 .by(__.propertyMap())
                 .map(this::map);
    }

    private GenericArtifactCoordinates map(Traverser<Map<String, Object>> t)
    {
        GenericArtifactCoordinatesEntity result = new GenericArtifactCoordinatesEntity();
        result.setNativeId(extractObject(Long.class, t.get().get("id")));
        result.setUuid(extractObject(String.class, t.get().get("uuid")));
        result.setVersion(extractObject(String.class, t.get().get("version")));

        Map<String, Object> coordinates = (Map<String, Object>) t.get().get("coordinates");
        coordinates.remove("uuid");
        coordinates.remove("version");
        coordinates.remove("created");
        coordinates.entrySet()
                   .stream()
                   .forEach(e -> result.setCoordinate(e.getKey().replace("coordinates.", ""),
                                                      extractPropertyList(String.class, e.getValue()).iterator().next()));

        return result;
    }

    @Override
    public UnfoldEntityTraversal<Vertex, Vertex> unfold(GenericArtifactCoordinates entity)
    {
        EntityTraversal<Vertex, Vertex> t = __.<Vertex>identity();

        if (entity.getVersion() != null)
        {
            t = t.property(single, "version", entity.getVersion());
        }

        for (Entry<String, String> coordinateEntry : entity.getCoordinates().entrySet())
        {
            if (coordinateEntry.getValue() == null)
            {
                continue;
            }
            t = t.property(single, "coordinates." + coordinateEntry.getKey(), coordinateEntry.getValue());
        }

        return new UnfoldEntityTraversal<>(Vertices.GENERIC_ARTIFACT_COORDINATES, entity, t);
    }

    @Override
    public EntityTraversal<Vertex, Element> cascade()
    {
        throw new UnsupportedOperationException();
    }

}
