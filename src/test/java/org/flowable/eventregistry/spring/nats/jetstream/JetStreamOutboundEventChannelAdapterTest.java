package org.flowable.eventregistry.spring.nats.jetstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.Message;
import io.nats.client.api.PublishAck;
import org.flowable.common.engine.api.FlowableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JetStreamOutboundEventChannelAdapterTest {

    private JetStream jetStream;
    private JetStreamOutboundEventChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        jetStream = mock(JetStream.class);
        adapter = new JetStreamOutboundEventChannelAdapter(jetStream, "order.completed", null, "orderChannel");
    }

    @Test
    void sendEvent_publishesToJetStream() throws Exception {
        PublishAck ack = mock(PublishAck.class);
        when(ack.getSeqno()).thenReturn(42L);
        when(jetStream.publish(any(Message.class))).thenReturn(ack);

        adapter.sendEvent("{\"orderId\":123}", Collections.emptyMap());

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(jetStream).publish(captor.capture());
        Message published = captor.getValue();
        assertThat(published.getSubject()).isEqualTo("order.completed");
        assertThat(new String(published.getData(), StandardCharsets.UTF_8)).isEqualTo("{\"orderId\":123}");
    }

    @Test
    void sendEvent_propagatesHeaders() throws Exception {
        PublishAck ack = mock(PublishAck.class);
        when(ack.getSeqno()).thenReturn(1L);
        when(jetStream.publish(any(Message.class))).thenReturn(ack);

        adapter.sendEvent("{\"orderId\":456}", Map.of("X-Trace-Id", "trace-abc", "X-Source", "test"));

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(jetStream).publish(captor.capture());
        Message published = captor.getValue();
        assertThat(published.getHeaders()).isNotNull();
        assertThat(published.getHeaders().getLast("X-Trace-Id")).isEqualTo("trace-abc");
        assertThat(published.getHeaders().getLast("X-Source")).isEqualTo("test");
    }

    @Test
    void sendEvent_publishFails_throwsFlowableException() throws Exception {
        when(jetStream.publish(any(Message.class)))
                .thenThrow(new IOException("connection lost"));

        assertThatThrownBy(() -> adapter.sendEvent("{\"orderId\":789}", Collections.emptyMap()))
                .isInstanceOf(FlowableException.class)
                .hasMessageContaining("orderChannel")
                .hasMessageContaining("order.completed");
    }
}
