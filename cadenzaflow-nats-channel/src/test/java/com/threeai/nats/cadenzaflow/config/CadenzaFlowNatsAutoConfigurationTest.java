package com.threeai.nats.cadenzaflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import org.cadenzaflow.bpm.engine.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CadenzaFlowNatsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CadenzaFlowNatsAutoConfiguration.class))
            .withBean(Connection.class, () -> mock(Connection.class))
            .withBean(JetStream.class, () -> mock(JetStream.class))
            .withBean(RuntimeService.class, () -> mock(RuntimeService.class));

    @Test
    void autoConfiguration_registersSubscriptionRegistrar() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(NatsSubscriptionRegistrar.class);
        });
    }

    @Test
    void autoConfiguration_registersPrototypeDelegates() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(com.threeai.nats.cadenzaflow.outbound.NatsPublishDelegate.class);
            assertThat(context).hasSingleBean(com.threeai.nats.cadenzaflow.outbound.JetStreamPublishDelegate.class);
            assertThat(context).hasSingleBean(com.threeai.nats.cadenzaflow.outbound.NatsRequestReplyDelegate.class);
        });
    }

    @Test
    void autoConfiguration_registersStreamManager() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(com.threeai.nats.core.jetstream.JetStreamStreamManager.class);
        });
    }
}
