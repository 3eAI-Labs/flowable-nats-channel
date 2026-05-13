package com.threeai.nats.cadenzaflow.outbound;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.nats.client.JetStream;
import io.nats.client.api.PublishAck;
import io.nats.client.impl.NatsMessage;
import org.cadenzaflow.bpm.engine.ProcessEngineException;
import org.cadenzaflow.bpm.engine.delegate.DelegateExecution;
import org.cadenzaflow.bpm.engine.delegate.Expression;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JetStreamPublishDelegateTest {

    private JetStream jetStream;
    private DelegateExecution execution;
    private JetStreamPublishDelegate delegate;

    @BeforeEach
    void setUp() throws Exception {
        jetStream = mock(JetStream.class);
        execution = mock(DelegateExecution.class);
        delegate = new JetStreamPublishDelegate(jetStream, null);
        when(jetStream.publish(any(NatsMessage.class))).thenReturn(mock(PublishAck.class));
    }

    @Test
    void execute_publishesToJetStream() throws Exception {
        Expression subjectExpr = mockExpression("order.created");
        Expression payloadExpr = mockExpression("orderPayload");
        delegate.setSubject(subjectExpr);
        delegate.setPayloadVariable(payloadExpr);

        when(execution.getVariable("orderPayload")).thenReturn("{\"orderId\":1}");

        delegate.execute(execution);

        verify(jetStream).publish(any(NatsMessage.class));
    }

    @Test
    void execute_publishFails_throwsException() throws Exception {
        Expression subjectExpr = mockExpression("order.created");
        Expression payloadExpr = mockExpression("orderPayload");
        delegate.setSubject(subjectExpr);
        delegate.setPayloadVariable(payloadExpr);

        when(execution.getVariable("orderPayload")).thenReturn("data");
        when(jetStream.publish(any(NatsMessage.class)))
                .thenThrow(new RuntimeException("JetStream unavailable"));

        assertThatThrownBy(() -> delegate.execute(execution))
                .isInstanceOf(ProcessEngineException.class)
                .hasMessageContaining("JetStream");
    }

    private Expression mockExpression(String value) {
        Expression expr = mock(Expression.class);
        when(expr.getValue(any(DelegateExecution.class))).thenReturn(value);
        return expr;
    }
}
