package org.flowable.eventregistry.spring.nats;

import java.time.Duration;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import org.flowable.eventregistry.api.EventRegistry;
import org.flowable.eventregistry.api.InboundEventChannelAdapter;
import org.flowable.eventregistry.model.InboundChannelModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NatsInboundEventChannelAdapter implements InboundEventChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(NatsInboundEventChannelAdapter.class);

    private final Connection connection;
    private final String subject;
    private final String queueGroup;

    private InboundChannelModel inboundChannelModel;
    private EventRegistry eventRegistry;
    private Dispatcher dispatcher;

    public NatsInboundEventChannelAdapter(Connection connection, String subject, String queueGroup) {
        this.connection = connection;
        this.subject = subject;
        this.queueGroup = queueGroup;
    }

    @Override
    public void setInboundChannelModel(InboundChannelModel inboundChannelModel) {
        this.inboundChannelModel = inboundChannelModel;
    }

    @Override
    public void setEventRegistry(EventRegistry eventRegistry) {
        this.eventRegistry = eventRegistry;
    }

    public void subscribe() {
        this.dispatcher = connection.createDispatcher();
        if (queueGroup != null && !queueGroup.isBlank()) {
            dispatcher.subscribe(subject, queueGroup, this::handleMessage);
        } else {
            dispatcher.subscribe(subject, this::handleMessage);
        }
        log.info("NATS inbound channel '{}': subscribed to subject '{}'{}",
                inboundChannelModel.getKey(), subject,
                queueGroup != null ? " with queue group '" + queueGroup + "'" : "");
    }

    public void unsubscribe() {
        if (dispatcher != null) {
            try {
                dispatcher.drain(Duration.ofSeconds(5));
            } catch (Exception e) {
                log.warn("NATS inbound channel '{}': error draining dispatcher",
                        inboundChannelModel.getKey(), e);
            }
            connection.closeDispatcher(dispatcher);
            dispatcher = null;
            log.info("NATS inbound channel '{}': unsubscribed from subject '{}'",
                    inboundChannelModel.getKey(), subject);
        }
    }

    void handleMessage(Message message) {
        if (message.getData() == null || message.getData().length == 0) {
            log.warn("NATS inbound channel '{}': empty message on subject '{}', skipping",
                    inboundChannelModel.getKey(), message.getSubject());
            return;
        }

        try {
            NatsInboundEvent event = new NatsInboundEvent(message);
            eventRegistry.eventReceived(inboundChannelModel, event);
        } catch (Exception e) {
            log.error("NATS inbound channel '{}': failed to process message on subject '{}'",
                    inboundChannelModel.getKey(), message.getSubject(), e);
        }
    }
}
