package com.threeai.nats.camunda.outbound;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.nats.client.Connection;
import io.nats.client.impl.NatsMessage;
import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.Expression;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NatsPublishDelegateTest {

    private Connection connection;
    private DelegateExecution execution;
    private NatsPublishDelegate delegate;

    @BeforeEach
    void setUp() {
        connection = mock(Connection.class);
        execution = mock(DelegateExecution.class);
        delegate = new NatsPublishDelegate(connection, null);
    }

    @Test
    void execute_publishesMessage() {
        Expression subjectExpr = mockExpression("order.created");
        Expression payloadExpr = mockExpression("orderPayload");
        delegate.setSubject(subjectExpr);
        delegate.setPayloadVariable(payloadExpr);

        when(execution.getVariable("orderPayload")).thenReturn("{\"orderId\":1}");

        delegate.execute(execution);

        ArgumentCaptor<NatsMessage> captor = ArgumentCaptor.forClass(NatsMessage.class);
        verify(connection).publish(captor.capture());
        NatsMessage published = captor.getValue();
        assert new String(published.getData()).equals("{\"orderId\":1}");
        assert published.getSubject().equals("order.created");
    }

    @Test
    void execute_propagatesHeaders() {
        Expression subjectExpr = mockExpression("order.created");
        Expression payloadExpr = mockExpression("orderPayload");
        delegate.setSubject(subjectExpr);
        delegate.setPayloadVariable(payloadExpr);

        when(execution.getVariable("orderPayload")).thenReturn("data");
        when(execution.getVariable("traceId")).thenReturn("trace-123");

        delegate.execute(execution);

        verify(connection).publish(any(NatsMessage.class));
    }

    @Test
    void execute_missingSubject_throwsException() {
        assertThatThrownBy(() -> delegate.execute(execution))
                .isInstanceOf(ProcessEngineException.class)
                .hasMessageContaining("subject");
    }

    private Expression mockExpression(String value) {
        Expression expr = mock(Expression.class);
        when(expr.getValue(any(DelegateExecution.class))).thenReturn(value);
        return expr;
    }
}
