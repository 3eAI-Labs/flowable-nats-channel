package org.flowable.eventregistry.spring.nats.config;

import java.io.IOException;

import com.threeai.nats.core.NatsConnectionFactory;
import com.threeai.nats.core.NatsProperties;
import com.threeai.nats.core.jetstream.JetStreamStreamManager;
import com.threeai.nats.core.metrics.NatsChannelMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import org.flowable.eventregistry.api.EventRegistry;
import org.flowable.eventregistry.spring.nats.NatsChannelDefinitionProcessor;
import org.flowable.eventregistry.spring.nats.requestreply.NatsRequestReplyDelegate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;

@AutoConfiguration
@ConditionalOnClass({ Connection.class, EventRegistry.class })
@EnableConfigurationProperties(NatsProperties.class)
public class FlowableNatsAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public Connection natsConnection(NatsProperties props) throws IOException, InterruptedException {
        return NatsConnectionFactory.create(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public JetStream natsJetStream(Connection connection) throws IOException {
        return connection.jetStream();
    }

    @Bean
    @ConditionalOnMissingBean
    public JetStreamStreamManager jetStreamStreamManager() {
        return new JetStreamStreamManager();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(MeterRegistry.class)
    public NatsChannelMetrics natsChannelMetrics(MeterRegistry registry) {
        return new NatsChannelMetrics(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public NatsChannelDefinitionProcessor natsChannelDefinitionProcessor(
            Connection connection,
            JetStream jetStream,
            JetStreamStreamManager streamManager,
            @Autowired(required = false) NatsChannelMetrics metrics) {
        return new NatsChannelDefinitionProcessor(connection, jetStream, streamManager, metrics);
    }

    @Bean
    @Scope("prototype")
    public NatsRequestReplyDelegate natsRequestReply(
            Connection connection,
            @Autowired(required = false) NatsChannelMetrics metrics) {
        return new NatsRequestReplyDelegate(connection, metrics);
    }
}
