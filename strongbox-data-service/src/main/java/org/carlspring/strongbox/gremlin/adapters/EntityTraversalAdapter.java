package org.carlspring.strongbox.gremlin.adapters;

import org.apache.tinkerpop.gremlin.structure.Element;
import org.carlspring.strongbox.data.domain.DomainObject;
import org.carlspring.strongbox.gremlin.dsl.EntityTraversal;
import org.springframework.data.repository.Repository;

/**
 * Makes possible to translate {@link Repository} operations on underlying graph using Gremlin query language.
 * Every entity should have corresponding {@link EntityTraversalAdapter} implementation.
 *
 * @param <S>
 * @param <E>
 *
 * @author sbespalov
 */
public interface EntityTraversalAdapter<S extends Element, E extends DomainObject>
{

    /**
     * Element label associated with the entity type.
     *
     * @return target element label
     */
    String label();

    /**
     * Folds the current traversal into entity instance.
     *
     * @return traversal with entity instance
     */
    EntityTraversal<S, E> fold();

    /**
     * Unfolds the entity instance on graph.
     *
     * @param entity entity to unfold
     * @return traversal with element associated with the entity
     */
    UnfoldEntityTraversal<S, S> unfold(E entity);

    /**
     * Performs entity cascade operations on graph.
     *
     * @return traversal with cascaded graph elements
     */
    EntityTraversal<S, Element> cascade();

}
