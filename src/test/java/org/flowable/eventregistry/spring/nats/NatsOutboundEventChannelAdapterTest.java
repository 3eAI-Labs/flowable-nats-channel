package org.flowable.eventregistry.spring.nats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

import io.nats.client.Connection;
import io.nats.client.Message;
import org.flowable.common.engine.api.FlowableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(connection).publish(captor.capture());
        Message published = captor.getValue();
        assertThat(published.getSubject()).isEqualTo("order.completed");
        assertThat(new String(published.getData(), StandardCharsets.UTF_8)).isEqualTo("{\"orderId\":123}");
    }

    @Test
    void sendEvent_withHeaders_publishesMessageWithHeaders() {
        when(connection.getStatus()).thenReturn(Connection.Status.CONNECTED);

        adapter.sendEvent("{\"orderId\":456}", Map.of("X-Trace-Id", "trace-123"));

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(connection).publish(captor.capture());
        Message published = captor.getValue();
        assertThat(published.getSubject()).isEqualTo("order.completed");
        assertThat(published.getHeaders()).isNotNull();
        assertThat(published.getHeaders().getLast("X-Trace-Id")).isEqualTo("trace-123");
    }

    @Test
    void sendEvent_connectionClosed_throwsFlowableException() {
        when(connection.getStatus()).thenReturn(Connection.Status.CLOSED);

        assertThatThrownBy(() -> adapter.sendEvent("{\"orderId\":123}", Collections.emptyMap()))
                .isInstanceOf(FlowableException.class)
                .hasMessageContaining("order.completed");
    }
}
