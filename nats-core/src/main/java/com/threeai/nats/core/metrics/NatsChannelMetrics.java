package com.threeai.nats.core.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

public class NatsChannelMetrics {

    private final MeterRegistry registry;

    public NatsChannelMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public Counter consumeCount(String subject, String channel) {
        return Counter.builder("nats.inbound.consumed")
                .tag("subject", subject).tag("channel", channel).register(registry);
    }

    public Counter consumeErrorCount(String subject, String channel) {
        return Counter.builder("nats.inbound.errors")
                .tag("subject", subject).tag("channel", channel).register(registry);
    }

    public Counter ackCount(String subject, String channel) {
        return Counter.builder("nats.jetstream.inbound.ack")
                .tag("subject", subject).tag("channel", channel).register(registry);
    }

    public Counter nakCount(String subject, String channel) {
        return Counter.builder("nats.jetstream.inbound.nak")
                .tag("subject", subject).tag("channel", channel).register(registry);
    }

    public Counter dlqCount(String subject, String channel) {
        return Counter.builder("nats.jetstream.inbound.dlq")
                .tag("subject", subject).tag("channel", channel).register(registry);
    }

    public Counter publishCount(String subject, String channel) {
        return Counter.builder("nats.outbound.published")
                .tag("subject", subject).tag("channel", channel).register(registry);
    }

    public Counter publishErrorCount(String subject, String channel) {
        return Counter.builder("nats.outbound.errors")
                .tag("subject", subject).tag("channel", channel).register(registry);
    }

    public Counter jsPublishCount(String subject, String channel) {
        return Counter.builder("nats.jetstream.outbound.published")
                .tag("subject", subject).tag("channel", channel).register(registry);
    }

    public Counter jsPublishErrorCount(String subject, String channel) {
        return Counter.builder("nats.jetstream.outbound.errors")
                .tag("subject", subject).tag("channel", channel).register(registry);
    }

    public Timer processingTimer(String subject, String channel) {
        return Timer.builder("nats.inbound.processing.duration")
                .tag("subject", subject).tag("channel", channel).register(registry);
    }

    public Counter reconnectCount() {
        return Counter.builder("nats.connection.reconnects").register(registry);
    }

    public Counter slowConsumerCount() {
        return Counter.builder("nats.connection.slow.consumers").register(registry);
    }

    // Request-Reply metrics (single subject tag — no channel concept)
    public Counter requestReplyCount(String subject) {
        return Counter.builder("nats.requestreply.requests")
                .tag("subject", subject).register(registry);
    }

    public Counter requestReplyErrorCount(String subject) {
        return Counter.builder("nats.requestreply.errors")
                .tag("subject", subject).register(registry);
    }
}
