package com.threeai.nats.cadenzaflow.config;

import java.io.IOException;

import com.threeai.nats.cadenzaflow.outbound.JetStreamPublishDelegate;
import com.threeai.nats.cadenzaflow.outbound.NatsPublishDelegate;
import com.threeai.nats.cadenzaflow.outbound.NatsRequestReplyDelegate;
import com.threeai.nats.core.NatsConnectionFactory;
import com.threeai.nats.core.NatsProperties;
import com.threeai.nats.core.jetstream.JetStreamStreamManager;
import com.threeai.nats.core.metrics.NatsChannelMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import org.cadenzaflow.bpm.engine.RuntimeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;

@AutoConfiguration
@ConditionalOnClass(org.cadenzaflow.bpm.engine.ProcessEngine.class)
@EnableConfigurationProperties({NatsProperties.class, CadenzaFlowNatsProperties.class})
public class CadenzaFlowNatsAutoConfiguration {

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
    public NatsSubscriptionRegistrar natsSubscriptionRegistrar(
            CadenzaFlowNatsProperties properties,
            Connection connection,
            JetStream jetStream,
            JetStreamStreamManager streamManager,
            RuntimeService runtimeService,
            @Autowired(required = false) NatsChannelMetrics metrics) {
        return new NatsSubscriptionRegistrar(
                properties, connection, jetStream, streamManager, runtimeService, metrics);
    }

    @Bean
    @Scope("prototype")
    public NatsPublishDelegate natsPublishDelegate(
            Connection connection,
            @Autowired(required = false) NatsChannelMetrics metrics) {
        return new NatsPublishDelegate(connection, metrics);
    }

    @Bean
    @Scope("prototype")
    public JetStreamPublishDelegate jetStreamPublishDelegate(
            JetStream jetStream,
            @Autowired(required = false) NatsChannelMetrics metrics) {
        return new JetStreamPublishDelegate(jetStream, metrics);
    }

    @Bean
    @Scope("prototype")
    public NatsRequestReplyDelegate natsRequestReply(
            Connection connection,
            @Autowired(required = false) NatsChannelMetrics metrics) {
        return new NatsRequestReplyDelegate(connection, metrics);
    }
}
