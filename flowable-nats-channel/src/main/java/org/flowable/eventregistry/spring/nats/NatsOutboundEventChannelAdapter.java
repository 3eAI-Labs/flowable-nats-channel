package org.flowable.eventregistry.spring.nats;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import io.nats.client.Connection;
import io.nats.client.impl.NatsMessage;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.eventregistry.api.OutboundEventChannelAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NatsOutboundEventChannelAdapter implements OutboundEventChannelAdapter<String> {

    private static final Logger log = LoggerFactory.getLogger(NatsOutboundEventChannelAdapter.class);

    private final Connection connection;
    private final String subject;

    public NatsOutboundEventChannelAdapter(Connection connection, String subject) {
        this.connection = connection;
        this.subject = subject;
    }

    @Override
    public void sendEvent(String rawEvent, Map<String, Object> headerMap) {
        Connection.Status status = connection.getStatus();
        if (status == Connection.Status.CLOSED || status == Connection.Status.DISCONNECTED) {
            log.error("Connection not available for publish",
                    kv("subject", subject),
                    kv("status", status.name()));
            throw new FlowableException(
                    "NATS outbound channel: connection not available for subject '" + subject
                    + "' (status: " + status + ")");
        }
        NatsMessage message = NatsMessage.builder()
                .subject(subject)
                .data(rawEvent.getBytes(StandardCharsets.UTF_8))
                .headers(NatsHeaderUtils.toNatsHeaders(headerMap))
                .build();
        connection.publish(message);
    }
}
