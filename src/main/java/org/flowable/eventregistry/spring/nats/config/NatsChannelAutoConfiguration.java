package org.flowable.eventregistry.spring.nats.config;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.io.IOException;

import io.nats.client.Connection;
import io.nats.client.ErrorListener;
import io.nats.client.JetStream;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.micrometer.core.instrument.MeterRegistry;
import org.flowable.eventregistry.api.EventRegistry;
import org.flowable.eventregistry.spring.nats.NatsChannelDefinitionProcessor;
import org.flowable.eventregistry.spring.nats.jetstream.JetStreamStreamManager;
import org.flowable.eventregistry.spring.nats.metrics.NatsChannelMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass({ Connection.class, EventRegistry.class })
@EnableConfigurationProperties(NatsProperties.class)
public class NatsChannelAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(NatsChannelAutoConfiguration.class);

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public Connection natsConnection(NatsProperties props) throws IOException, InterruptedException {
        Options.Builder builder = new Options.Builder()
                .server(props.getUrl())
                .connectionTimeout(props.getConnectionTimeout())
                .maxReconnects(props.getMaxReconnects())
                .reconnectWait(props.getReconnectWait())
                .connectionListener((conn, event) -> {
                    switch (event) {
                        case CONNECTED -> log.info("NATS connected", kv("host", conn.getServerInfo().getHost()));
                        case RECONNECTED -> log.warn("NATS reconnected", kv("host", conn.getServerInfo().getHost()));
                        case DISCONNECTED -> log.warn("NATS disconnected");
                        case CLOSED -> log.info("NATS connection closed");
                        default -> log.debug("NATS connection event", kv("event", event.name()));
                    }
                })
                .errorListener(new ErrorListener() {
                    @Override
                    public void errorOccurred(Connection conn, String error) {
                        log.error("NATS error", kv("error", error));
                    }

                    @Override
                    public void exceptionOccurred(Connection conn, Exception exp) {
                        log.error("NATS exception", exp);
                    }

                    @Override
                    public void slowConsumerDetected(Connection conn, io.nats.client.Consumer consumer) {
                        log.warn("NATS slow consumer detected", kv("pending_messages", consumer.getPendingMessageCount()));
                    }
                });

        configureAuth(builder, props);

        return Nats.connect(builder.build());
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

    private void configureAuth(Options.Builder builder, NatsProperties props) {
        if (props.getCredentialsFile() != null) {
            builder.authHandler(Nats.credentials(props.getCredentialsFile()));
        } else if (props.getNkeyFile() != null) {
            builder.authHandler(Nats.credentials(null, props.getNkeyFile()));
        } else if (props.getToken() != null) {
            builder.token(props.getToken().toCharArray());
        } else if (props.getUsername() != null) {
            builder.userInfo(props.getUsername(), props.getPassword());
        }
    }
}
