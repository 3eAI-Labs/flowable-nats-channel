package org.flowable.eventregistry.spring.nats;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.eventregistry.api.ChannelModelProcessor;
import org.flowable.eventregistry.api.EventRegistry;
import org.flowable.eventregistry.api.EventRepositoryService;
import org.flowable.eventregistry.model.ChannelModel;
import org.flowable.eventregistry.spring.nats.channel.NatsInboundChannelModel;
import org.flowable.eventregistry.spring.nats.channel.NatsOutboundChannelModel;
import org.flowable.eventregistry.spring.nats.jetstream.JetStreamInboundEventChannelAdapter;
import org.flowable.eventregistry.spring.nats.jetstream.JetStreamOutboundEventChannelAdapter;
import org.flowable.eventregistry.spring.nats.jetstream.JetStreamStreamManager;
import org.flowable.eventregistry.spring.nats.metrics.NatsChannelMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NatsChannelDefinitionProcessor implements ChannelModelProcessor {

    private static final Logger log = LoggerFactory.getLogger(NatsChannelDefinitionProcessor.class);

    private final Connection connection;
    private final JetStream jetStream;
    private final JetStreamStreamManager streamManager;
    private final NatsChannelMetrics metrics;
    private final Map<String, NatsInboundEventChannelAdapter> coreInboundAdapters = new ConcurrentHashMap<>();
    private final Map<String, JetStreamInboundEventChannelAdapter> jetStreamInboundAdapters = new ConcurrentHashMap<>();

    public NatsChannelDefinitionProcessor(Connection connection, JetStream jetStream,
            JetStreamStreamManager streamManager, NatsChannelMetrics metrics) {
        this.connection = connection;
        this.jetStream = jetStream;
        this.streamManager = streamManager;
        this.metrics = metrics;
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
            if (inboundModel.isJetstream()) {
                registerJetStreamInbound(inboundModel, tenantId, eventRegistry);
            } else {
                registerInbound(inboundModel, tenantId, eventRegistry);
            }
        } else if (channelModel instanceof NatsOutboundChannelModel outboundModel) {
            if (outboundModel.isJetstream()) {
                registerJetStreamOutbound(outboundModel);
            } else {
                registerOutbound(outboundModel);
            }
        }
    }

    @Override
    public void unregisterChannelModel(ChannelModel channelModel, String tenantId,
            EventRepositoryService eventRepositoryService) {

        String key = resolveKey(channelModel, tenantId);
        NatsInboundEventChannelAdapter coreAdapter = coreInboundAdapters.remove(key);
        if (coreAdapter != null) {
            coreAdapter.unsubscribe();
        }
        JetStreamInboundEventChannelAdapter jsAdapter = jetStreamInboundAdapters.remove(key);
        if (jsAdapter != null) {
            jsAdapter.unsubscribe();
        }
    }

    private void registerInbound(NatsInboundChannelModel model, String tenantId,
            EventRegistry eventRegistry) {

        validateSubject(model.getSubject(), model.getKey());

        NatsInboundEventChannelAdapter adapter = new NatsInboundEventChannelAdapter(
                connection, model.getSubject(), model.getQueueGroup());

        model.setInboundEventChannelAdapter(adapter);
        adapter.setInboundChannelModel(model);
        adapter.setEventRegistry(eventRegistry);
        adapter.subscribe();

        coreInboundAdapters.put(resolveKey(model, tenantId), adapter);
    }

    private void registerJetStreamInbound(NatsInboundChannelModel model, String tenantId,
            EventRegistry eventRegistry) {

        validateSubject(model.getSubject(), model.getKey());

        if (model.isAutoCreateStream() && model.getStreamName() != null) {
            streamManager.ensureStream(model.getStreamName(), model.getSubject(), connection);
        }

        String dlqSubject = model.getDlqSubject();
        if (dlqSubject == null) {
            dlqSubject = "dlq." + model.getSubject();
        }

        JetStreamInboundEventChannelAdapter adapter = new JetStreamInboundEventChannelAdapter(
                connection, jetStream, model.getSubject(), model.getMaxDeliver(),
                dlqSubject, metrics, model.getKey());

        model.setInboundEventChannelAdapter(adapter);
        adapter.setInboundChannelModel(model);
        adapter.setEventRegistry(eventRegistry);
        adapter.subscribe();

        jetStreamInboundAdapters.put(resolveKey(model, tenantId), adapter);
    }

    private void registerOutbound(NatsOutboundChannelModel model) {
        validateSubject(model.getSubject(), model.getKey());

        NatsOutboundEventChannelAdapter adapter = new NatsOutboundEventChannelAdapter(
                connection, model.getSubject());
        model.setOutboundEventChannelAdapter(adapter);
    }

    private void registerJetStreamOutbound(NatsOutboundChannelModel model) {
        validateSubject(model.getSubject(), model.getKey());

        if (model.isAutoCreateStream() && model.getStreamName() != null) {
            streamManager.ensureStream(model.getStreamName(), model.getSubject(), connection);
        }

        JetStreamOutboundEventChannelAdapter adapter = new JetStreamOutboundEventChannelAdapter(
                jetStream, model.getSubject(), metrics, model.getKey());
        model.setOutboundEventChannelAdapter(adapter);
    }

    private void validateSubject(String subject, String channelKey) {
        if (subject == null || subject.isBlank()) {
            throw new FlowableException(
                    "NATS channel '" + channelKey + "': subject is required");
        }
    }

    private String resolveKey(ChannelModel channelModel, String tenantId) {
        if (tenantId != null) {
            return tenantId + "#" + channelModel.getKey();
        }
        return channelModel.getKey();
    }
}
