package org.carlspring.strongbox.config.janusgraph;

import org.carlspring.strongbox.db.schema.StrongboxSchema;
import org.janusgraph.core.JanusGraph;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.strongbox.db.server.JanusGraphConfiguration;
import org.strongbox.db.server.JanusGraphProperties;
import org.strongbox.db.server.JanusGraphServer;
import org.strongbox.db.server.JanusGraphWithRemoteCassandra;

/**
 * @author sbespalov
 */
@Configuration
@Conditional(RemoteDbServerConfiguration.class)
public class RemoteDbServerConfiguration implements Condition
{

    @Bean
    JanusGraphServer embeddedDbServer(DelegatingIdBlockQueueSupplier idBlockQueueSupplier, JanusGraphConfiguration janusGraphConfiguration)
    {
        return new JanusGraphWithRemoteCassandra(janusGraphConfiguration, idBlockQueueSupplier);
    }

    @Bean
    JanusGraph JanusGraph(JanusGraphServer server)
        throws Exception
    {
        return new StrongboxSchema().createSchema(server.getJanusGraph());
    }

    @Override
    public boolean matches(ConditionContext conditionContext,
                           AnnotatedTypeMetadata metadata)

    {
        JanusGraphDbProfile profile = JanusGraphDbProfile.resolveProfile((ConfigurableEnvironment) conditionContext.getEnvironment());

        return profile.getName().equals(JanusGraphDbProfile.PROFILE_REMOTE);
    }

}
