package org.carlspring.strongbox.data.domain;

import java.io.Serializable;

import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/**
 * @author sbespalov
 *
 */
public interface DomainObject extends Serializable
{
    default Long getNativeId()
    {
        return null;
    }

    String getUuid();
    
    void applyUnfold(Traverser<Vertex> t);

}