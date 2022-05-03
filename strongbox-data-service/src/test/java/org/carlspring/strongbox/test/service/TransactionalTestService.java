package org.carlspring.strongbox.test.service;

import static org.carlspring.strongbox.db.schema.Vertices.ARTIFACT;

import org.carlspring.strongbox.gremlin.dsl.EntityTraversalSource;
import org.carlspring.strongbox.gremlin.tx.TransactionContext;

import javax.inject.Inject;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class TransactionalTestService
{

    private static final String VERTEX_LABEL = ARTIFACT;

    @Inject
    @TransactionContext
    private Graph graph;

    @Transactional
    public Long countVertices()
    {
        return traversal().V().hasLabel(VERTEX_LABEL).count().next();
    }

    @Transactional
    public Long createVertexWithCommit()
    {
        return createVertex(traversal());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Object createVertexWithNestedCommit()
    {
        return createVertexWithCommit();
    }

    @Transactional
    public Object createVerticesWithException()
    {
        createVertexWithCommit();
        createVertexWithNestedCommit();

        throw new RuntimeException();
    }

    protected Long createVertex(GraphTraversalSource t)
    {
        Long vertexId = (Long) t.addV(VERTEX_LABEL).next().id();
        return ((vertexId << 5) >> 9)/16;
    }

    private EntityTraversalSource traversal()
    {
        return graph.traversal(EntityTraversalSource.class);
    }

}
