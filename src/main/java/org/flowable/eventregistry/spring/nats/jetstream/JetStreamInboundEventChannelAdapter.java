package org.flowable.eventregistry.spring.nats.jetstream;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.JetStream;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PushSubscribeOptions;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.impl.NatsJetStreamMetaData;
import io.micrometer.core.instrument.Timer;
import org.flowable.eventregistry.api.EventRegistry;
import org.flowable.eventregistry.api.InboundEventChannelAdapter;
import org.flowable.eventregistry.model.InboundChannelModel;
import org.flowable.eventregistry.spring.nats.NatsInboundEvent;
import org.flowable.eventregistry.spring.nats.metrics.NatsChannelMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class JetStreamInboundEventChannelAdapter implements InboundEventChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(JetStreamInboundEventChannelAdapter.class);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final Connection connection;
    private final JetStream jetStream;
    private final String subject;
    private final int maxDeliver;
    private final String dlqSubject;
    private final NatsChannelMetrics metrics;
    private final String channelKey;

    private EventRegistry eventRegistry;
    private InboundChannelModel inboundChannelModel;
    private Dispatcher dispatcher;
    private ExecutorService executor;

    public JetStreamInboundEventChannelAdapter(Connection connection, JetStream jetStream,
            String subject, int maxDeliver, String dlqSubject,
            NatsChannelMetrics metrics, String channelKey) {
        this.connection = connection;
        this.jetStream = jetStream;
        this.subject = subject;
        this.maxDeliver = maxDeliver;
        this.dlqSubject = dlqSubject;
        this.metrics = metrics;
        this.channelKey = channelKey;
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
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.dispatcher = connection.createDispatcher();
        try {
            ConsumerConfiguration cc = ConsumerConfiguration.builder()
                    .ackWait(Duration.ofSeconds(30))
                    .maxDeliver(maxDeliver)
                    .build();
            PushSubscribeOptions opts = PushSubscribeOptions.builder()
                    .configuration(cc)
                    .build();
            JetStreamSubscription sub = jetStream.subscribe(subject, dispatcher,
                    msg -> executor.submit(() -> handleMessage(msg)), false, opts);
            log.info("Subscribed to JetStream",
                    kv("channel", channelKey),
                    kv("subject", subject));
        } catch (Exception e) {
            log.error("Failed to subscribe to JetStream",
                    kv("channel", channelKey),
                    kv("subject", subject), e);
            throw new org.flowable.common.engine.api.FlowableException(
                    "Failed to subscribe to JetStream subject '" + subject + "'", e);
        }
    }

    public void unsubscribe() {
        if (dispatcher != null) {
            try {
                dispatcher.drain(Duration.ofSeconds(10));
            } catch (Exception e) {
                log.warn("Error draining JetStream dispatcher",
                        kv("channel", channelKey), e);
            }
        }
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }

    void handleMessage(Message msg) {
        String traceId = extractHeader(msg, "X-Trace-Id");
        if (traceId != null) {
            MDC.put("trace_id", traceId);
        }
        try {
            byte[] data = msg.getData();
            if (data == null || data.length == 0) {
                log.debug("Empty message body, skipping",
                        kv("channel", channelKey),
                        kv("subject", msg.getSubject()));
                msg.ack();
                return;
            }

            // Check if max deliveries exceeded -> DLQ path
            long deliveryCount = getDeliveryCount(msg);
            if (deliveryCount > maxDeliver) {
                log.warn("Max deliveries exceeded, routing to DLQ",
                        kv("channel", channelKey),
                        kv("subject", msg.getSubject()),
                        kv("delivery_count", deliveryCount),
                        kv("max_deliver", maxDeliver));
                publishToDlq(msg);
                if (metrics != null) {
                    metrics.dlqCount(subject, channelKey).increment();
                }
                msg.ack();
                return;
            }

            // Normal processing
            Timer.Sample sample = metrics != null ? Timer.start() : null;
            NatsInboundEvent event = new NatsInboundEvent(msg);
            eventRegistry.eventReceived(inboundChannelModel, event);

            if (sample != null) {
                sample.stop(metrics.processingTimer(subject, channelKey));
            }
            if (metrics != null) {
                metrics.ackCount(subject, channelKey).increment();
            }
            msg.ack();
            log.debug("Message processed and acked",
                    kv("channel", channelKey),
                    kv("subject", msg.getSubject()));

        } catch (Exception e) {
            log.error("Error processing JetStream message",
                    kv("channel", channelKey),
                    kv("subject", msg.getSubject()), e);
            if (metrics != null) {
                metrics.nakCount(subject, channelKey).increment();
            }
            nakWithBackoff(msg);
        } finally {
            MDC.remove("trace_id");
        }
    }

    private long getDeliveryCount(Message msg) {
        try {
            NatsJetStreamMetaData metaData = msg.metaData();
            return metaData.deliveredCount();
        } catch (Exception e) {
            log.debug("Could not retrieve message metadata",
                    kv("channel", channelKey), e);
            return 1;
        }
    }

    private void nakWithBackoff(Message msg) {
        try {
            long deliveryCount = msg.metaData().deliveredCount();
            Duration backoff = calculateBackoff(deliveryCount);
            msg.nakWithDelay(backoff);
            log.debug("Message nacked with delay",
                    kv("channel", channelKey),
                    kv("delay", backoff));
        } catch (Exception e) {
            log.warn("Failed to get metadata for backoff, falling back to plain nak",
                    kv("channel", channelKey), e);
            msg.nak();
        }
    }

    Duration calculateBackoff(long deliveryCount) {
        long seconds = (long) Math.pow(2, deliveryCount - 1);
        Duration backoff = Duration.ofSeconds(seconds);
        return backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
    }

    private void publishToDlq(Message msg) {
        if (dlqSubject == null) {
            log.warn("DLQ subject not configured, discarding message",
                    kv("channel", channelKey));
            return;
        }
        byte[] data = msg.getData();
        try {
            jetStream.publish(dlqSubject, data);
            log.info("Message published to DLQ via JetStream",
                    kv("channel", channelKey),
                    kv("dlq_subject", dlqSubject));
        } catch (Exception e) {
            log.warn("JetStream DLQ publish failed, falling back to core NATS",
                    kv("channel", channelKey),
                    kv("dlq_subject", dlqSubject), e);
            try {
                connection.publish(dlqSubject, data);
                log.info("Message published to DLQ via core NATS",
                        kv("channel", channelKey),
                        kv("dlq_subject", dlqSubject));
            } catch (Exception ex) {
                log.error("DLQ publish failed on both JetStream and core NATS",
                        kv("channel", channelKey),
                        kv("dlq_subject", dlqSubject), ex);
            }
        }
    }

    private String extractHeader(Message msg, String key) {
        if (msg.getHeaders() == null) {
            return null;
        }
        return msg.getHeaders().getLast(key);
    }
}
