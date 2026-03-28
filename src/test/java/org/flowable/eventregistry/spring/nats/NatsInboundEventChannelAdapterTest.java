package org.flowable.eventregistry.spring.nats;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import io.nats.client.MessageHandler;
import io.nats.client.Subscription;
import org.flowable.eventregistry.api.EventRegistry;
import org.flowable.eventregistry.spring.nats.channel.NatsInboundChannelModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NatsInboundEventChannelAdapterTest {

    private Connection connection;
    private Dispatcher dispatcher;
    private EventRegistry eventRegistry;
    private NatsInboundChannelModel channelModel;
    private NatsInboundEventChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        connection = mock(Connection.class);
        dispatcher = mock(Dispatcher.class);
        eventRegistry = mock(EventRegistry.class);

        when(connection.createDispatcher()).thenReturn(dispatcher);
        when(dispatcher.subscribe(anyString(), any(MessageHandler.class))).thenReturn(mock(Subscription.class));
        when(dispatcher.subscribe(anyString(), anyString(), any(MessageHandler.class))).thenReturn(mock(Subscription.class));

        channelModel = new NatsInboundChannelModel();
        channelModel.setKey("testChannel");
        channelModel.setSubject("order.new");

        adapter = new NatsInboundEventChannelAdapter(connection, "order.new", null);
        adapter.setInboundChannelModel(channelModel);
        adapter.setEventRegistry(eventRegistry);
    }

    @Test
    void subscribe_createsDispatcherAndSubscribes() {
        adapter.subscribe();

        verify(connection).createDispatcher();
        verify(dispatcher).subscribe(eq("order.new"), any(MessageHandler.class));
    }

    @Test
    void subscribe_withQueueGroup_subscribesWithQueueGroup() {
        adapter = new NatsInboundEventChannelAdapter(connection, "order.new", "order-service");
        adapter.setInboundChannelModel(channelModel);
        adapter.setEventRegistry(eventRegistry);

        adapter.subscribe();

        verify(dispatcher).subscribe(eq("order.new"), eq("order-service"), any(MessageHandler.class));
    }

    @Test
    void handleMessage_validMessage_triggersEventReceived() {
        Message message = mock(Message.class);
        when(message.getData()).thenReturn("{\"orderId\":1}".getBytes(StandardCharsets.UTF_8));
        when(message.getHeaders()).thenReturn(null);
        when(message.getSubject()).thenReturn("order.new");

        adapter.handleMessage(message);

        verify(eventRegistry).eventReceived(eq(channelModel), any(NatsInboundEvent.class));
    }

    @Test
    void handleMessage_emptyBody_skipsWithoutTrigger() {
        Message message = mock(Message.class);
        when(message.getData()).thenReturn(new byte[0]);
        when(message.getSubject()).thenReturn("order.new");

        adapter.handleMessage(message);

        verify(eventRegistry, never()).eventReceived(any(), any(NatsInboundEvent.class));
    }

    @Test
    void handleMessage_processingError_continuesSubscription() {
        // First message causes exception
        Message badMessage = mock(Message.class);
        when(badMessage.getData()).thenReturn("{\"bad\":true}".getBytes(StandardCharsets.UTF_8));
        when(badMessage.getHeaders()).thenReturn(null);
        when(badMessage.getSubject()).thenReturn("order.new");
        doThrow(new RuntimeException("simulated error"))
                .when(eventRegistry).eventReceived(any(), any(NatsInboundEvent.class));

        // Should NOT throw — adapter catches and logs
        adapter.handleMessage(badMessage);

        // Reset mock
        reset(eventRegistry);

        // Second message should still be processed
        Message goodMessage = mock(Message.class);
        when(goodMessage.getData()).thenReturn("{\"good\":true}".getBytes(StandardCharsets.UTF_8));
        when(goodMessage.getHeaders()).thenReturn(null);
        when(goodMessage.getSubject()).thenReturn("order.new");

        adapter.handleMessage(goodMessage);

        verify(eventRegistry).eventReceived(eq(channelModel), any(NatsInboundEvent.class));
    }
}
