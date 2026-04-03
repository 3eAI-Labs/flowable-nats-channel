package com.threeai.nats.core.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NatsChannelMetricsTest {

    private SimpleMeterRegistry registry;
    private NatsChannelMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new NatsChannelMetrics(registry);
    }

    @Test
    void counters_registeredAndIncrementCorrectly() {
        Counter consume = metrics.consumeCount("order.new", "orderChannel");
        Counter ack = metrics.ackCount("order.new", "orderChannel");
        Counter nak = metrics.nakCount("order.new", "orderChannel");
        Counter dlq = metrics.dlqCount("order.new", "orderChannel");
        Counter publish = metrics.publishCount("order.out", "outChannel");
        Counter publishError = metrics.publishErrorCount("order.out", "outChannel");
        Counter jsPublish = metrics.jsPublishCount("order.out", "outChannel");
        Counter jsPublishError = metrics.jsPublishErrorCount("order.out", "outChannel");
        Counter reconnect = metrics.reconnectCount();
        Counter slowConsumer = metrics.slowConsumerCount();

        consume.increment();
        ack.increment();
        nak.increment();
        dlq.increment();
        reconnect.increment();

        assertThat(consume.count()).isEqualTo(1.0);
        assertThat(ack.count()).isEqualTo(1.0);
        assertThat(nak.count()).isEqualTo(1.0);
        assertThat(dlq.count()).isEqualTo(1.0);
        assertThat(reconnect.count()).isEqualTo(1.0);
    }

    @Test
    void requestReplyCounters_registeredAndIncrementCorrectly() {
        Counter requests = metrics.requestReplyCount("task.send-sms");
        Counter errors = metrics.requestReplyErrorCount("task.send-sms");
        requests.increment();
        errors.increment();
        assertThat(requests.count()).isEqualTo(1.0);
        assertThat(errors.count()).isEqualTo(1.0);
        assertThat(requests.getId().getName()).isEqualTo("nats.requestreply.requests");
        assertThat(requests.getId().getTag("subject")).isEqualTo("task.send-sms");
    }

    @Test
    void processingTimer_registeredCorrectly() {
        Timer timer = metrics.processingTimer("order.new", "orderChannel");

        assertThat(timer).isNotNull();
        assertThat(timer.getId().getName()).isEqualTo("nats.inbound.processing.duration");
        assertThat(timer.getId().getTag("subject")).isEqualTo("order.new");
        assertThat(timer.getId().getTag("channel")).isEqualTo("orderChannel");
    }
}
