package com.threeai.nats.camunda.outbound;

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
import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.Expression;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class NatsRequestReplyDelegateTest {

    private Connection connection;
    private DelegateExecution execution;
    private NatsRequestReplyDelegate delegate;

    @BeforeEach
    void setUp() {
        connection = mock(Connection.class);
        execution = mock(DelegateExecution.class);
        delegate = new NatsRequestReplyDelegate(connection, null);
    }

    @Test
    void execute_sendsRequestAndStoresReply() throws Exception {
        Expression subjectExpr = mockExpression("task.process");
        Expression timeoutExpr = mockExpression("30s");
        Expression resultExpr = mockExpression("result");
        Expression payloadExpr = mockExpression("requestPayload");
        delegate.setSubject(subjectExpr);
        delegate.setTimeout(timeoutExpr);
        delegate.setResultVariable(resultExpr);
        delegate.setPayloadVariable(payloadExpr);

        when(execution.getVariable("requestPayload")).thenReturn("{\"task\":\"do-it\"}");

        Message reply = mock(Message.class);
        when(reply.getData()).thenReturn("{\"status\":\"done\"}".getBytes(StandardCharsets.UTF_8));
        when(connection.request(eq("task.process"), any(byte[].class), any(Duration.class)))
                .thenReturn(reply);

        delegate.execute(execution);

        verify(execution).setVariable("result", "{\"status\":\"done\"}");
    }

    @Test
    void execute_timeout_throwsException() throws Exception {
        Expression subjectExpr = mockExpression("task.process");
        Expression payloadExpr = mockExpression("requestPayload");
        delegate.setSubject(subjectExpr);
        delegate.setPayloadVariable(payloadExpr);

        when(execution.getVariable("requestPayload")).thenReturn("data");
        when(connection.request(any(String.class), any(byte[].class), any(Duration.class)))
                .thenReturn(null);

        assertThatThrownBy(() -> delegate.execute(execution))
                .isInstanceOf(ProcessEngineException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    void execute_nullPayload_sendsEmptyBody() throws Exception {
        Expression subjectExpr = mockExpression("task.process");
        Expression payloadExpr = mockExpression("requestPayload");
        delegate.setSubject(subjectExpr);
        delegate.setPayloadVariable(payloadExpr);

        when(execution.getVariable("requestPayload")).thenReturn(null);

        Message reply = mock(Message.class);
        when(reply.getData()).thenReturn("ok".getBytes(StandardCharsets.UTF_8));
        when(connection.request(eq("task.process"), eq(new byte[0]), any(Duration.class)))
                .thenReturn(reply);

        delegate.execute(execution);

        verify(connection).request(eq("task.process"), eq(new byte[0]), any(Duration.class));
    }

    @Test
    void execute_propagatesTraceId() throws Exception {
        Expression subjectExpr = mockExpression("task.process");
        Expression payloadExpr = mockExpression("requestPayload");
        delegate.setSubject(subjectExpr);
        delegate.setPayloadVariable(payloadExpr);

        when(execution.getVariable("requestPayload")).thenReturn("data");
        when(execution.getVariable("traceId")).thenReturn("trace-rr-999");

        final String[] capturedTraceId = {null};
        Message reply = mock(Message.class);
        when(reply.getData()).thenReturn("ok".getBytes(StandardCharsets.UTF_8));
        when(connection.request(any(String.class), any(byte[].class), any(Duration.class)))
                .thenAnswer(invocation -> {
                    capturedTraceId[0] = MDC.get("trace_id");
                    return reply;
                });

        delegate.execute(execution);

        assertThat(capturedTraceId[0]).isEqualTo("trace-rr-999");
        assertThat(MDC.get("trace_id")).isNull();
    }

    private Expression mockExpression(String value) {
        Expression expr = mock(Expression.class);
        when(expr.getValue(any(DelegateExecution.class))).thenReturn(value);
        return expr;
    }
}
