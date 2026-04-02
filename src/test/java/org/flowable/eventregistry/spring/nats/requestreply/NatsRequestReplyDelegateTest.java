package org.flowable.eventregistry.spring.nats.requestreply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import io.nats.client.Connection;
import io.nats.client.Message;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.delegate.Expression;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NatsRequestReplyDelegateTest {

    private Connection connection;
    private DelegateExecution execution;
    private NatsRequestReplyDelegate delegate;

    @BeforeEach
    void setUp() {
        connection = mock(Connection.class);
        execution = mock(DelegateExecution.class);
        when(execution.getProcessInstanceId()).thenReturn("proc-123");

        delegate = new NatsRequestReplyDelegate(connection, null);
    }

    @Test
    void execute_sendsRequestAndStoresReply() throws Exception {
        delegate.setSubject(mockExpression("task.send-sms"));
        delegate.setTimeout(mockExpression("5s"));
        delegate.setResultVariable(mockExpression("smsResult"));
        delegate.setPayloadVariable(mockExpression("smsPayload"));

        when(execution.getVariable("smsPayload")).thenReturn("{\"to\":\"+905551234567\"}");

        Message reply = mock(Message.class);
        when(reply.getData()).thenReturn("{\"status\":\"sent\"}".getBytes(StandardCharsets.UTF_8));
        when(connection.request(eq("task.send-sms"), any(byte[].class), eq(Duration.ofSeconds(5))))
                .thenReturn(reply);

        delegate.execute(execution);

        verify(execution).setVariable("smsResult", "{\"status\":\"sent\"}");
    }

    @Test
    void execute_timeout_throwsFlowableException() throws Exception {
        delegate.setSubject(mockExpression("task.send-sms"));

        when(execution.getVariable("natsRequestPayload")).thenReturn("test");
        when(connection.request(any(), any(byte[].class), any(Duration.class)))
                .thenReturn(null);

        assertThatThrownBy(() -> delegate.execute(execution))
                .isInstanceOf(FlowableException.class)
                .hasMessageContaining("timeout")
                .hasMessageContaining("task.send-sms");
    }

    @Test
    void execute_dynamicSubject_resolvesFromExecution() throws Exception {
        Expression subjectExpr = mock(Expression.class);
        when(subjectExpr.getValue(execution)).thenReturn("task.ota.provision");
        delegate.setSubject(subjectExpr);

        when(execution.getVariable("natsRequestPayload")).thenReturn("data");

        Message reply = mock(Message.class);
        when(reply.getData()).thenReturn("ok".getBytes(StandardCharsets.UTF_8));
        when(connection.request(eq("task.ota.provision"), any(byte[].class), any(Duration.class)))
                .thenReturn(reply);

        delegate.execute(execution);

        verify(connection).request(eq("task.ota.provision"), any(byte[].class), any(Duration.class));
    }

    @Test
    void execute_connectionError_throwsFlowableException() throws Exception {
        delegate.setSubject(mockExpression("task.fail"));

        when(execution.getVariable("natsRequestPayload")).thenReturn("data");
        when(connection.request(any(), any(byte[].class), any(Duration.class)))
                .thenThrow(new RuntimeException("connection lost"));

        assertThatThrownBy(() -> delegate.execute(execution))
                .isInstanceOf(FlowableException.class)
                .hasMessageContaining("task.fail")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void execute_nullPayload_sendsEmptyBody() throws Exception {
        delegate.setSubject(mockExpression("task.empty"));

        when(execution.getVariable("natsRequestPayload")).thenReturn(null);

        Message reply = mock(Message.class);
        when(reply.getData()).thenReturn("ok".getBytes(StandardCharsets.UTF_8));
        when(connection.request(eq("task.empty"), eq(new byte[0]), any(Duration.class)))
                .thenReturn(reply);

        delegate.execute(execution);

        verify(connection).request(eq("task.empty"), eq(new byte[0]), any(Duration.class));
    }

    @Test
    void execute_propagatesTraceId() throws Exception {
        delegate.setSubject(mockExpression("task.trace"));

        when(execution.getVariable("traceId")).thenReturn("trace-abc-123");
        when(execution.getVariable("natsRequestPayload")).thenReturn("data");

        Message reply = mock(Message.class);
        when(reply.getData()).thenReturn("ok".getBytes(StandardCharsets.UTF_8));
        when(connection.request(any(), any(byte[].class), any(Duration.class)))
                .thenReturn(reply);

        delegate.execute(execution);

        // MDC is thread-local and cleared in finally
        assertThat(org.slf4j.MDC.get("trace_id")).isNull();
    }

    private Expression mockExpression(String value) {
        Expression expr = mock(Expression.class);
        when(expr.getValue(any())).thenReturn(value);
        return expr;
    }
}
