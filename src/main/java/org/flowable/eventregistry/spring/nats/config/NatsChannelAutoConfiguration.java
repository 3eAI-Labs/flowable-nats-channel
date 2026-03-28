package org.flowable.eventregistry.spring.nats.config;

import java.io.IOException;

import io.nats.client.Connection;
import io.nats.client.ErrorListener;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.flowable.eventregistry.api.EventRegistry;
import org.flowable.eventregistry.spring.nats.NatsChannelDefinitionProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
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
                        case CONNECTED -> log.info("NATS connected: {}", conn.getServerInfo().getHost());
                        case RECONNECTED -> log.warn("NATS reconnected: {}", conn.getServerInfo().getHost());
                        case DISCONNECTED -> log.warn("NATS disconnected");
                        case CLOSED -> log.info("NATS connection closed");
                        default -> log.debug("NATS connection event: {}", event);
                    }
                })
                .errorListener(new ErrorListener() {
                    @Override
                    public void errorOccurred(Connection conn, String error) {
                        log.error("NATS error: {}", error);
                    }

                    @Override
                    public void exceptionOccurred(Connection conn, Exception exp) {
                        log.error("NATS exception", exp);
                    }

                    @Override
                    public void slowConsumerDetected(Connection conn, io.nats.client.Consumer consumer) {
                        log.warn("NATS slow consumer detected (pending: {} messages)", consumer.getPendingMessageCount());
                    }
                });

        configureAuth(builder, props);

        return Nats.connect(builder.build());
    }

    @Bean
    @ConditionalOnMissingBean
    public NatsChannelDefinitionProcessor natsChannelDefinitionProcessor(Connection connection) {
        return new NatsChannelDefinitionProcessor(connection);
    }

    private void configureAuth(Options.Builder builder, NatsProperties props) {
        if (props.getCredentialsFile() != null) {
            builder.authHandler(Nats.credentials(props.getCredentialsFile()));
        } else if (props.getToken() != null) {
            builder.token(props.getToken().toCharArray());
        } else if (props.getUsername() != null) {
            builder.userInfo(props.getUsername(), props.getPassword());
        }
    }
}
