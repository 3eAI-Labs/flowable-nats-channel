package com.threeai.nats.cadenzaflow.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.impl.Headers;
import org.cadenzaflow.bpm.engine.RuntimeService;
import org.cadenzaflow.bpm.engine.runtime.MessageCorrelationBuilder;
import org.cadenzaflow.bpm.engine.runtime.MessageCorrelationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class NatsMessageCorrelationSubscriberTest {

    private Connection connection;
    private RuntimeService runtimeService;
    private MessageCorrelationBuilder correlationBuilder;
    private SubscriptionConfig config;
    private NatsMessageCorrelationSubscriber subscriber;

    @BeforeEach
    void setUp() {
        connection = mock(Connection.class);
        runtimeService = mock(RuntimeService.class);
        correlationBuilder = mock(MessageCorrelationBuilder.class);

        when(runtimeService.createMessageCorrelation(any())).thenReturn(correlationBuilder);
        when(correlationBuilder.processInstanceBusinessKey(any())).thenReturn(correlationBuilder);
        when(correlationBuilder.setVariables(anyMap())).thenReturn(correlationBuilder);
        when(correlationBuilder.correlateWithResult()).thenReturn(mock(MessageCorrelationResult.class));

        config = new SubscriptionConfig();
        config.setSubject("order.new");
        config.setMessageName("OrderReceived");

        subscriber = new NatsMessageCorrelationSubscriber(connection, runtimeService, config, null);
    }

    @Test
    void handleMessage_correlatesSuccessfully() {
        Message msg = createMockMessage("{\"orderId\":\"123\"}", null);

        subscriber.handleMessage(msg);

        verify(runtimeService).createMessageCorrelation("OrderReceived");
        verify(correlationBuilder).setVariables(anyMap());
        verify(correlationBuilder).correlateWithResult();
    }

    @Test
    void handleMessage_correlationFails_logsError() {
        Message msg = createMockMessage("{\"orderId\":\"123\"}", null);
        when(correlationBuilder.correlateWithResult())
                .thenThrow(new RuntimeException("No process found"));

        subscriber.handleMessage(msg);

        verify(runtimeService).createMessageCorrelation("OrderReceived");
    }

    @Test
    void handleMessage_emptyBody_skips() {
        Message msg = createMockMessage("", null);

        subscriber.handleMessage(msg);

        verify(runtimeService, never()).createMessageCorrelation(any());
    }

    @Test
    void handleMessage_propagatesTraceId() {
        Headers headers = new Headers();
        headers.add("X-Trace-Id", "trace-abc-789");
        Message msg = createMockMessage("{\"orderId\":\"1\"}", headers);

        final String[] capturedTraceId = {null};
        when(correlationBuilder.correlateWithResult()).thenAnswer(invocation -> {
            capturedTraceId[0] = MDC.get("trace_id");
            return mock(MessageCorrelationResult.class);
        });

        subscriber.handleMessage(msg);

        assertThat(capturedTraceId[0]).isEqualTo("trace-abc-789");
        assertThat(MDC.get("trace_id")).isNull();
    }

    @Test
    void handleMessage_usesBusinessKeyFromHeader() {
        Headers headers = new Headers();
        headers.add("X-Business-Key", "BK-001");
        config.setBusinessKeyHeader("X-Business-Key");
        Message msg = createMockMessage("{\"orderId\":\"1\"}", headers);

        subscriber.handleMessage(msg);

        verify(correlationBuilder).processInstanceBusinessKey("BK-001");
    }

    private Message createMockMessage(String body, Headers headers) {
        Message msg = mock(Message.class);
        when(msg.getData()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        when(msg.getHeaders()).thenReturn(headers);
        when(msg.getSubject()).thenReturn("order.new");
        return msg;
    }
}
