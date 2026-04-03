package org.flowable.eventregistry.spring.nats.jetstream;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import io.nats.client.JetStream;
import io.nats.client.api.PublishAck;
import io.nats.client.impl.NatsMessage;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.eventregistry.api.OutboundEventChannelAdapter;
import org.flowable.eventregistry.spring.nats.NatsHeaderUtils;
import org.flowable.eventregistry.spring.nats.metrics.NatsChannelMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JetStreamOutboundEventChannelAdapter implements OutboundEventChannelAdapter<String> {

    private static final Logger log = LoggerFactory.getLogger(JetStreamOutboundEventChannelAdapter.class);

    private final JetStream jetStream;
    private final String subject;
    private final NatsChannelMetrics metrics;
    private final String channelKey;

    public JetStreamOutboundEventChannelAdapter(JetStream jetStream, String subject,
            NatsChannelMetrics metrics, String channelKey) {
        this.jetStream = jetStream;
        this.subject = subject;
        this.metrics = metrics;
        this.channelKey = channelKey;
    }

    @Override
    public void sendEvent(String rawEvent, Map<String, Object> headerMap) {
        try {
            byte[] data = rawEvent.getBytes(StandardCharsets.UTF_8);
            NatsMessage message = NatsMessage.builder()
                    .subject(subject)
                    .data(data)
                    .headers(NatsHeaderUtils.toNatsHeaders(headerMap))
                    .build();
            PublishAck ack = jetStream.publish(message);
            if (metrics != null) {
                metrics.jsPublishCount(subject, channelKey).increment();
            }
            log.debug("Published to JetStream",
                    kv("channel", channelKey),
                    kv("subject", subject),
                    kv("stream_seq", ack.getSeqno()));
        } catch (Exception e) {
            if (metrics != null) {
                metrics.jsPublishErrorCount(subject, channelKey).increment();
            }
            log.error("JetStream publish failed",
                    kv("channel", channelKey),
                    kv("subject", subject), e);
            throw new FlowableException(
                    "JetStream publish failed for channel '" + channelKey
                    + "' on subject '" + subject + "'", e);
        }
    }
}
