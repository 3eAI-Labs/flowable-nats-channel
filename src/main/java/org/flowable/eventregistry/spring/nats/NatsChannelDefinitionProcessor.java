package org.flowable.eventregistry.spring.nats;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.nats.client.Connection;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.eventregistry.api.ChannelModelProcessor;
import org.flowable.eventregistry.api.EventRegistry;
import org.flowable.eventregistry.api.EventRepositoryService;
import org.flowable.eventregistry.model.ChannelModel;
import org.flowable.eventregistry.spring.nats.channel.NatsInboundChannelModel;
import org.flowable.eventregistry.spring.nats.channel.NatsOutboundChannelModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NatsChannelDefinitionProcessor implements ChannelModelProcessor {

    private static final Logger log = LoggerFactory.getLogger(NatsChannelDefinitionProcessor.class);

    private final Connection connection;
    private final Map<String, NatsInboundEventChannelAdapter> inboundAdapters = new ConcurrentHashMap<>();

    public NatsChannelDefinitionProcessor(Connection connection) {
        this.connection = connection;
    }

    @Override
    public boolean canProcess(ChannelModel channelModel) {
        return channelModel instanceof NatsInboundChannelModel
                || channelModel instanceof NatsOutboundChannelModel;
    }

    @Override
    public boolean canProcessIfChannelModelAlreadyRegistered(ChannelModel channelModel) {
        return channelModel instanceof NatsOutboundChannelModel;
    }

    @Override
    public void registerChannelModel(ChannelModel channelModel, String tenantId,
            EventRegistry eventRegistry, EventRepositoryService eventRepositoryService,
            boolean fallbackToDefaultTenant) {

        if (channelModel instanceof NatsInboundChannelModel inboundModel) {
            registerInbound(inboundModel, tenantId, eventRegistry);
        } else if (channelModel instanceof NatsOutboundChannelModel outboundModel) {
            registerOutbound(outboundModel);
        }
    }

    @Override
    public void unregisterChannelModel(ChannelModel channelModel, String tenantId,
            EventRepositoryService eventRepositoryService) {

        String key = resolveKey(channelModel, tenantId);
        NatsInboundEventChannelAdapter adapter = inboundAdapters.remove(key);
        if (adapter != null) {
            adapter.unsubscribe();
        }
    }

    private void registerInbound(NatsInboundChannelModel model, String tenantId,
            EventRegistry eventRegistry) {

        validateSubject(model.getSubject(), model.getKey());
        validateJetstream(model.isJetstream(), model.getKey());

        NatsInboundEventChannelAdapter adapter = new NatsInboundEventChannelAdapter(
                connection, model.getSubject(), model.getQueueGroup());

        model.setInboundEventChannelAdapter(adapter);
        adapter.setInboundChannelModel(model);
        adapter.setEventRegistry(eventRegistry);
        adapter.subscribe();

        inboundAdapters.put(resolveKey(model, tenantId), adapter);
    }

    private void registerOutbound(NatsOutboundChannelModel model) {
        validateSubject(model.getSubject(), model.getKey());
        validateJetstream(model.isJetstream(), model.getKey());

        NatsOutboundEventChannelAdapter adapter = new NatsOutboundEventChannelAdapter(
                connection, model.getSubject());
        model.setOutboundEventChannelAdapter(adapter);
    }

    private void validateSubject(String subject, String channelKey) {
        if (subject == null || subject.isBlank()) {
            throw new FlowableException(
                    "NATS channel '" + channelKey + "': subject is required");
        }
    }

    private void validateJetstream(boolean jetstream, String channelKey) {
        if (jetstream) {
            throw new FlowableException(
                    "NATS channel '" + channelKey + "': JetStream is not yet supported (planned for Phase 2)");
        }
    }

    private String resolveKey(ChannelModel channelModel, String tenantId) {
        if (tenantId != null) {
            return tenantId + "#" + channelModel.getKey();
        }
        return channelModel.getKey();
    }
}
