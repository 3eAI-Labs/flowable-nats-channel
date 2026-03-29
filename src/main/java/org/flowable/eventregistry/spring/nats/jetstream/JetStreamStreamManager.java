package org.flowable.eventregistry.spring.nats.jetstream;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.io.IOException;

import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import org.flowable.common.engine.api.FlowableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JetStreamStreamManager {

    private static final Logger log = LoggerFactory.getLogger(JetStreamStreamManager.class);

    public void ensureStream(String streamName, String subject, Connection connection) {
        try {
            JetStreamManagement jsm = connection.jetStreamManagement();
            try {
                jsm.getStreamInfo(streamName);
                log.debug("Stream exists", kv("stream", streamName));
            } catch (JetStreamApiException e) {
                if (e.getErrorCode() == 404) {
                    StreamConfiguration config = StreamConfiguration.builder()
                            .name(streamName)
                            .subjects(subject)
                            .retentionPolicy(RetentionPolicy.Limits)
                            .storageType(StorageType.File)
                            .build();
                    jsm.addStream(config);
                    log.info("Stream created", kv("stream", streamName), kv("subject", subject));
                } else {
                    throw new FlowableException("Failed to check stream '" + streamName + "'", e);
                }
            }
        } catch (IOException e) {
            throw new FlowableException("I/O error while managing stream '" + streamName + "'", e);
        } catch (FlowableException e) {
            throw e;
        } catch (Exception e) {
            throw new FlowableException("Unexpected error managing stream '" + streamName + "'", e);
        }
    }
}
