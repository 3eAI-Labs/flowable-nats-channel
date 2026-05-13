package com.threeai.nats.cadenzaflow.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;

import com.threeai.nats.core.metrics.NatsChannelMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.Connection;
import io.nats.client.Nats;
import org.cadenzaflow.bpm.engine.delegate.DelegateExecution;
import org.cadenzaflow.bpm.engine.delegate.Expression;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class CadenzaFlowOutboundIntegrationTest {

    @Container
    static GenericContainer<?> natsContainer = new GenericContainer<>("nats:2.10-alpine")
            .withCommand("--jetstream")
            .withExposedPorts(4222);

    private Connection connection;
    private NatsChannelMetrics metrics;

    @BeforeEach
    void setUp() throws Exception {
        String url = "nats://" + natsContainer.getHost() + ":" + natsContainer.getMappedPort(4222);
        connection = Nats.connect(url);
        metrics = new NatsChannelMetrics(new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void outbound_publishDelegate_sendsToNats() throws Exception {
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        connection.createDispatcher().subscribe("cadenzaflow.out.test", msg ->
                received.add(new String(msg.getData(), StandardCharsets.UTF_8)));

        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.getVariable("orderPayload")).thenReturn("{\"status\":\"completed\"}");

        NatsPublishDelegate delegate = new NatsPublishDelegate(connection, metrics);
        delegate.setSubject(mockExpression("cadenzaflow.out.test"));
        delegate.setPayloadVariable(mockExpression("orderPayload"));

        delegate.execute(execution);
        connection.flush(Duration.ofSeconds(2));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(received).hasSize(1);
            assertThat(received.get(0)).isEqualTo("{\"status\":\"completed\"}");
        });
    }

    @Test
    void outbound_requestReply_workerResponds() throws Exception {
        connection.createDispatcher().subscribe("cadenzaflow.task.process", msg -> {
            String payload = new String(msg.getData(), StandardCharsets.UTF_8);
            String reply = "{\"result\":\"processed-" + payload.hashCode() + "\"}";
            connection.publish(msg.getReplyTo(), reply.getBytes(StandardCharsets.UTF_8));
        });

        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.getVariable("requestPayload")).thenReturn("{\"task\":\"doSomething\"}");

        NatsRequestReplyDelegate delegate = new NatsRequestReplyDelegate(connection, metrics);
        delegate.setSubject(mockExpression("cadenzaflow.task.process"));
        delegate.setTimeout(mockExpression("10s"));
        delegate.setResultVariable(mockExpression("taskResult"));
        delegate.setPayloadVariable(mockExpression("requestPayload"));

        delegate.execute(execution);

        org.mockito.Mockito.verify(execution).setVariable(
                org.mockito.ArgumentMatchers.eq("taskResult"),
                org.mockito.ArgumentMatchers.contains("processed-"));
    }

    private Expression mockExpression(String value) {
        Expression expr = mock(Expression.class);
        when(expr.getValue(any(DelegateExecution.class))).thenReturn(value);
        return expr;
    }
}
