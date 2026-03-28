package org.flowable.eventregistry.spring.nats;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import io.nats.client.Connection;
import org.flowable.common.engine.api.FlowableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NatsOutboundEventChannelAdapterTest {

    private Connection connection;
    private NatsOutboundEventChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        connection = mock(Connection.class);
        adapter = new NatsOutboundEventChannelAdapter(connection, "order.completed");
    }

    @Test
    void sendEvent_publishesMessage() {
        when(connection.getStatus()).thenReturn(Connection.Status.CONNECTED);

        adapter.sendEvent("{\"orderId\":123}", Collections.emptyMap());

        verify(connection).publish(eq("order.completed"),
                eq("{\"orderId\":123}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void sendEvent_connectionClosed_throwsFlowableException() {
        when(connection.getStatus()).thenReturn(Connection.Status.CLOSED);

        assertThatThrownBy(() -> adapter.sendEvent("{\"orderId\":123}", Collections.emptyMap()))
                .isInstanceOf(FlowableException.class)
                .hasMessageContaining("order.completed");
    }
}
