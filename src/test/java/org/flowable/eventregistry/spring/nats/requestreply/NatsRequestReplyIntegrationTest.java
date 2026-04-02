package org.flowable.eventregistry.spring.nats.requestreply;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.delegate.Expression;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class NatsRequestReplyIntegrationTest {

    @Container
    static GenericContainer<?> natsContainer = new GenericContainer<>("nats:2.10-alpine")
            .withExposedPorts(4222);

    private Connection connection;
    private Connection workerConnection;

    @BeforeEach
    void setUp() throws Exception {
        String natsUrl = "nats://" + natsContainer.getHost() + ":" + natsContainer.getMappedPort(4222);
        connection = Nats.connect(new Options.Builder().server(natsUrl).build());
        workerConnection = Nats.connect(new Options.Builder().server(natsUrl).build());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
        if (workerConnection != null) workerConnection.close();
    }

    @Test
    void requestReply_workerResponds_resultStored() throws Exception {
        // Worker subscribes and responds
        workerConnection.createDispatcher().subscribe("task.test.echo", "test-workers", msg -> {
            String request = new String(msg.getData(), StandardCharsets.UTF_8);
            workerConnection.publish(msg.getReplyTo(),
                    ("{\"echo\":\"" + request + "\"}").getBytes(StandardCharsets.UTF_8));
        });
        workerConnection.flush(Duration.ofSeconds(2));

        // Delegate
        NatsRequestReplyDelegate delegate = new NatsRequestReplyDelegate(connection, null);
        delegate.setSubject(mockExpression("task.test.echo"));
        delegate.setTimeout(mockExpression("5s"));
        delegate.setResultVariable(mockExpression("testResult"));
        delegate.setPayloadVariable(mockExpression("testPayload"));

        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.getProcessInstanceId()).thenReturn("test-proc-1");
        when(execution.getVariable("testPayload")).thenReturn("hello");

        delegate.execute(execution);

        verify(execution).setVariable("testResult", "{\"echo\":\"hello\"}");
    }

    @Test
    void requestReply_noWorker_timeoutThrowsException() {
        NatsRequestReplyDelegate delegate = new NatsRequestReplyDelegate(connection, null);
        delegate.setSubject(mockExpression("task.nobody.listening"));
        delegate.setTimeout(mockExpression("1s"));

        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.getProcessInstanceId()).thenReturn("test-proc-2");
        when(execution.getVariable("natsRequestPayload")).thenReturn("data");

        assertThatThrownBy(() -> delegate.execute(execution))
                .isInstanceOf(FlowableException.class)
                .hasMessageContaining("timeout")
                .hasMessageContaining("task.nobody.listening");
    }

    private Expression mockExpression(String value) {
        Expression expr = mock(Expression.class);
        when(expr.getValue(any())).thenReturn(value);
        return expr;
    }
}
