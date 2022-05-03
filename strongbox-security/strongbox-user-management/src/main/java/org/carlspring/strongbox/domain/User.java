package org.carlspring.strongbox.domain;

import java.time.LocalDateTime;
import java.util.Set;

import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.carlspring.strongbox.data.domain.DomainObject;

import com.fasterxml.jackson.annotation.JsonIgnore;

public interface User extends DomainObject
{

    @JsonIgnore
    String getUuid();

    @Override
    default void applyUnfold(Traverser<Vertex> t)
    {

    }

    default String getUsername()
    {
        return getUuid();
    }

    String getPassword();

    Set<SecurityRole> getRoles();

    String getSecurityTokenKey();

    Boolean isEnabled();

    LocalDateTime getLastUpdated();

    String getSourceId();

}
