package org.flowable.eventregistry.spring.nats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.MessageHandler;
import io.nats.client.Subscription;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.eventregistry.api.EventRegistry;
import org.flowable.eventregistry.api.EventRepositoryService;
import org.flowable.eventregistry.model.InboundChannelModel;
import org.flowable.eventregistry.spring.nats.channel.NatsInboundChannelModel;
import org.flowable.eventregistry.spring.nats.channel.NatsOutboundChannelModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NatsChannelDefinitionProcessorTest {

    private Connection connection;
    private EventRegistry eventRegistry;
    private EventRepositoryService eventRepositoryService;
    private NatsChannelDefinitionProcessor processor;

    @BeforeEach
    void setUp() {
        connection = mock(Connection.class);
        eventRegistry = mock(EventRegistry.class);
        eventRepositoryService = mock(EventRepositoryService.class);

        Dispatcher dispatcher = mock(Dispatcher.class);
        when(connection.createDispatcher()).thenReturn(dispatcher);
        when(dispatcher.subscribe(anyString(), any(MessageHandler.class))).thenReturn(mock(Subscription.class));
        when(dispatcher.subscribe(anyString(), anyString(), any(MessageHandler.class))).thenReturn(mock(Subscription.class));

        processor = new NatsChannelDefinitionProcessor(connection);
    }

    @Test
    void canProcess_natsInboundModel_returnsTrue() {
        NatsInboundChannelModel model = new NatsInboundChannelModel();
        assertThat(processor.canProcess(model)).isTrue();
    }

    @Test
    void canProcess_natsOutboundModel_returnsTrue() {
        NatsOutboundChannelModel model = new NatsOutboundChannelModel();
        assertThat(processor.canProcess(model)).isTrue();
    }

    @Test
    void canProcess_otherModel_returnsFalse() {
        InboundChannelModel model = new InboundChannelModel();
        assertThat(processor.canProcess(model)).isFalse();
    }

    @Test
    void registerInbound_validFields_subscribes() {
        NatsInboundChannelModel model = new NatsInboundChannelModel();
        model.setKey("testChannel");
        model.setSubject("order.new");
        model.setQueueGroup("order-service");

        processor.registerChannelModel(model, null, eventRegistry, eventRepositoryService, false);

        assertThat(model.getInboundEventChannelAdapter()).isNotNull();
    }

    @Test
    void registerInbound_noQueueGroup_subscribesWithout() {
        NatsInboundChannelModel model = new NatsInboundChannelModel();
        model.setKey("testChannel");
        model.setSubject("order.new");

        processor.registerChannelModel(model, null, eventRegistry, eventRepositoryService, false);

        assertThat(model.getInboundEventChannelAdapter()).isNotNull();
    }

    @Test
    void registerOutbound_validFields_createsAdapter() {
        NatsOutboundChannelModel model = new NatsOutboundChannelModel();
        model.setKey("testChannel");
        model.setSubject("order.completed");

        processor.registerChannelModel(model, null, eventRegistry, eventRepositoryService, false);

        assertThat(model.getOutboundEventChannelAdapter()).isNotNull();
    }

    @Test
    void registerInbound_missingSubject_throwsException() {
        NatsInboundChannelModel model = new NatsInboundChannelModel();
        model.setKey("testChannel");

        assertThatThrownBy(() ->
                processor.registerChannelModel(model, null, eventRegistry, eventRepositoryService, false))
                .isInstanceOf(FlowableException.class)
                .hasMessageContaining("subject");
    }

    @Test
    void registerInbound_jetstreamTrue_throwsException() {
        NatsInboundChannelModel model = new NatsInboundChannelModel();
        model.setKey("testChannel");
        model.setSubject("order.new");
        model.setJetstream(true);

        assertThatThrownBy(() ->
                processor.registerChannelModel(model, null, eventRegistry, eventRepositoryService, false))
                .isInstanceOf(FlowableException.class)
                .hasMessageContaining("JetStream")
                .hasMessageContaining("not yet supported");
    }
}
