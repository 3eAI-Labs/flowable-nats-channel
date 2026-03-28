package org.flowable.eventregistry.spring.nats;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import io.nats.client.Connection;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.eventregistry.api.OutboundEventChannelAdapter;

public class NatsOutboundEventChannelAdapter implements OutboundEventChannelAdapter<String> {

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
            throw new FlowableException(
                    "NATS outbound channel: connection not available for subject '" + subject
                    + "' (status: " + status + ")");
        }
        connection.publish(subject, rawEvent.getBytes(StandardCharsets.UTF_8));
    }
}
