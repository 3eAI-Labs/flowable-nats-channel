# Phase 2: JetStream Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add JetStream support to the Flowable NATS channel adapter with persistent messaging, ack/nack + DLQ, Micrometer metrics, structured logging, and virtual thread offloading.

**Architecture:** JetStream adapters live in a separate `jetstream/` package, connected via the existing `NatsChannelDefinitionProcessor` routing. Phase 1 Core NATS paths remain untouched. All message processing offloaded to virtual threads. Sync JetStream publish is safe because `spring.threads.virtual.enabled=true` with Java 21+.

**Tech Stack:** Java 21+, Spring Boot 3.x, Flowable 7.x, jnats 2.20+, NATS 2.10+ (nakWithDelay), Micrometer, Testcontainers, JUnit 5, Mockito

**Spec:** `docs/superpowers/specs/phase2-jetstream-adapter.md`

---

## File Structure

### New Files
```
src/main/java/org/flowable/eventregistry/spring/nats/
├── jetstream/
│   ├── JetStreamInboundEventChannelAdapter.java    # Push consumer, ack/nack, DLQ, virtual thread offload
│   ├── JetStreamOutboundEventChannelAdapter.java   # Sync publish with header propagation
│   └── JetStreamStreamManager.java                 # Auto-create stream guard
├── metrics/
│   └── NatsChannelMetrics.java                     # Micrometer counters + Timer
└── NatsHeaderUtils.java                            # Shared header conversion utility

src/test/java/org/flowable/eventregistry/spring/nats/
├── jetstream/
│   ├── JetStreamInboundEventChannelAdapterTest.java
│   ├── JetStreamOutboundEventChannelAdapterTest.java
│   ├── JetStreamStreamManagerTest.java
│   ├── JetStreamInboundIntegrationTest.java
│   ├── JetStreamOutboundIntegrationTest.java
│   └── JetStreamStreamManagerIntegrationTest.java
└── metrics/
    └── NatsChannelMetricsTest.java
```

### Modified Files
```
pom.xml                                                          # Java 21, logstash-logback, micrometer
src/main/java/.../channel/NatsInboundChannelModel.java           # +7 JetStream fields
src/main/java/.../channel/NatsOutboundChannelModel.java          # +2 JetStream fields
src/main/java/.../NatsChannelDefinitionProcessor.java            # JetStream routing + JetStream dependency
src/main/java/.../NatsInboundEventChannelAdapter.java            # Structured logging + MDC trace
src/main/java/.../NatsOutboundEventChannelAdapter.java           # Structured logging + header propagation
src/main/java/.../config/NatsChannelAutoConfiguration.java       # JetStream, Metrics, NKey beans
src/main/java/.../config/NatsProperties.java                     # +nkeyFile field
```

---

### Task 1: Java 21 Upgrade + Dependencies

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Update pom.xml**

Change `java.version` from 17 to 21. Add new dependencies:

```xml
<!-- In <properties> -->
<java.version>21</java.version>

<!-- In <dependencies> -->
<!-- Micrometer (optional runtime dependency) -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-core</artifactId>
    <optional>true</optional>
</dependency>

<!-- Logstash Logback Encoder (for structured logging kv()) -->
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>8.0</version>
</dependency>
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Verify all Phase 1 tests still pass**

Run: `mvn test -q`
Expected: 21 tests PASS

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "chore: upgrade to Java 21, add Micrometer and logstash-logback-encoder dependencies"
```

---

### Task 2: Structured Logging Retrofit (Phase 1 Classes)

**Files:**
- Modify: `src/main/java/org/flowable/eventregistry/spring/nats/NatsInboundEventChannelAdapter.java`
- Modify: `src/main/java/org/flowable/eventregistry/spring/nats/NatsOutboundEventChannelAdapter.java`
- Modify: `src/main/java/org/flowable/eventregistry/spring/nats/config/NatsChannelAutoConfiguration.java`

- [ ] **Step 1: Update NatsInboundEventChannelAdapter logging**

Add import: `import static net.logstash.logback.argument.StructuredArguments.kv;`

Replace all log statements with kv() format. Also add MDC trace propagation to `handleMessage()`:

```java
import org.slf4j.MDC;

public void subscribe() {
    this.dispatcher = connection.createDispatcher();
    if (queueGroup != null && !queueGroup.isBlank()) {
        dispatcher.subscribe(subject, queueGroup, this::handleMessage);
    } else {
        dispatcher.subscribe(subject, this::handleMessage);
    }
    log.info("Subscribed to NATS subject",
            kv("channel", inboundChannelModel.getKey()),
            kv("subject", subject),
            kv("queue_group", queueGroup));
}

public void unsubscribe() {
    if (dispatcher != null) {
        try {
            dispatcher.drain(Duration.ofSeconds(5));
        } catch (Exception e) {
            log.warn("Error draining dispatcher",
                    kv("channel", inboundChannelModel.getKey()), e);
        }
        connection.closeDispatcher(dispatcher);
        dispatcher = null;
        log.info("Unsubscribed from NATS subject",
                kv("channel", inboundChannelModel.getKey()),
                kv("subject", subject));
    }
}

void handleMessage(Message message) {
    String traceId = extractHeader(message, "X-Trace-Id");
    try {
        if (traceId != null) {
            MDC.put("trace_id", traceId);
        }

        if (message.getData() == null || message.getData().length == 0) {
            log.warn("Empty message received, skipping",
                    kv("channel", inboundChannelModel.getKey()),
                    kv("subject", message.getSubject()));
            return;
        }

        NatsInboundEvent event = new NatsInboundEvent(message);
        eventRegistry.eventReceived(inboundChannelModel, event);
    } catch (Exception e) {
        log.error("Message processing failed",
                kv("channel", inboundChannelModel.getKey()),
                kv("subject", message.getSubject()), e);
    } finally {
        MDC.remove("trace_id");
    }
}

private String extractHeader(Message msg, String key) {
    if (msg.getHeaders() != null && msg.getHeaders().containsKey(key)) {
        return msg.getHeaders().getLast(key);
    }
    return null;
}
```

- [ ] **Step 2: Update NatsOutboundEventChannelAdapter logging**

Add `kv()` import and update the error throw:

```java
import static net.logstash.logback.argument.StructuredArguments.kv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

private static final Logger log = LoggerFactory.getLogger(NatsOutboundEventChannelAdapter.class);

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
    connection.publish(subject, rawEvent.getBytes(StandardCharsets.UTF_8));
}
```

Note: Header propagation for Core NATS outbound is done in Task 3.

- [ ] **Step 3: Update NatsChannelAutoConfiguration logging**

Replace all `log.info/warn/error` statements in ConnectionListener and ErrorListener with `kv()` format:

```java
.connectionListener((conn, event) -> {
    switch (event) {
        case CONNECTED -> log.info("NATS connected",
                kv("host", conn.getServerInfo().getHost()));
        case RECONNECTED -> log.warn("NATS reconnected",
                kv("host", conn.getServerInfo().getHost()));
        case DISCONNECTED -> log.warn("NATS disconnected");
        case CLOSED -> log.info("NATS connection closed");
        default -> log.debug("NATS connection event",
                kv("event", event.name()));
    }
})
.errorListener(new ErrorListener() {
    @Override
    public void errorOccurred(Connection conn, String error) {
        log.error("NATS error", kv("error", error));
    }
    @Override
    public void exceptionOccurred(Connection conn, Exception exp) {
        log.error("NATS exception", exp);
    }
    @Override
    public void slowConsumerDetected(Connection conn, io.nats.client.Consumer consumer) {
        log.warn("NATS slow consumer detected",
                kv("pending_messages", consumer.getPendingMessageCount()));
    }
})
```

- [ ] **Step 4: Run all tests**

Run: `mvn test -q`
Expected: 21 tests PASS (logging changes don't break functionality)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/flowable/eventregistry/spring/nats/NatsInboundEventChannelAdapter.java \
        src/main/java/org/flowable/eventregistry/spring/nats/NatsOutboundEventChannelAdapter.java \
        src/main/java/org/flowable/eventregistry/spring/nats/config/NatsChannelAutoConfiguration.java
git commit -m "refactor: retrofit structured logging (kv format) and MDC trace propagation to Phase 1 classes"
```

---

### Task 3: Header Propagation Utility + Phase 1 Outbound Retrofit

**Files:**
- Create: `src/main/java/org/flowable/eventregistry/spring/nats/NatsHeaderUtils.java`
- Modify: `src/main/java/org/flowable/eventregistry/spring/nats/NatsOutboundEventChannelAdapter.java`

- [ ] **Step 1: Create NatsHeaderUtils**

```java
package org.flowable.eventregistry.spring.nats;

import java.util.Map;

import io.nats.client.impl.Headers;

public final class NatsHeaderUtils {

    private NatsHeaderUtils() {}

    public static Headers toNatsHeaders(Map<String, Object> headerMap) {
        if (headerMap == null || headerMap.isEmpty()) {
            return null;
        }
        Headers headers = new Headers();
        headerMap.forEach((key, value) -> {
            if (value != null) {
                headers.add(key, value.toString());
            }
        });
        return headers;
    }
}
```

- [ ] **Step 2: Update NatsOutboundEventChannelAdapter to propagate headers**

```java
import io.nats.client.impl.NatsMessage;

@Override
public void sendEvent(String rawEvent, Map<String, Object> headerMap) {
    Connection.Status status = connection.getStatus();
    if (status == Connection.Status.CLOSED || status == Connection.Status.DISCONNECTED) {
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
```

- [ ] **Step 3: Run all tests**

Run: `mvn test -q`
Expected: 21 tests PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/flowable/eventregistry/spring/nats/NatsHeaderUtils.java \
        src/main/java/org/flowable/eventregistry/spring/nats/NatsOutboundEventChannelAdapter.java
git commit -m "feat: add header propagation utility and retrofit Core NATS outbound adapter"
```

---

### Task 4: NKey Auth + NatsProperties Update

**Files:**
- Modify: `src/main/java/org/flowable/eventregistry/spring/nats/config/NatsProperties.java`
- Modify: `src/main/java/org/flowable/eventregistry/spring/nats/config/NatsChannelAutoConfiguration.java`

- [ ] **Step 1: Add nkeyFile to NatsProperties**

Add field after `credentialsFile`:

```java
private String nkeyFile;

public String getNkeyFile() {
    return nkeyFile;
}

public void setNkeyFile(String nkeyFile) {
    this.nkeyFile = nkeyFile;
}
```

- [ ] **Step 2: Update configureAuth in NatsChannelAutoConfiguration**

Insert NKey handling between credentialsFile and token:

```java
private void configureAuth(Options.Builder builder, NatsProperties props) {
    if (props.getCredentialsFile() != null) {
        builder.authHandler(Nats.credentials(props.getCredentialsFile()));
    } else if (props.getNkeyFile() != null) {
        builder.authHandler(AuthHandler.buildFromNkey(props.getNkeyFile()));
    } else if (props.getToken() != null) {
        builder.token(props.getToken().toCharArray());
    } else if (props.getUsername() != null) {
        builder.userInfo(props.getUsername(), props.getPassword());
    }
}
```

Note: Verify at implementation time whether `Nats.credentials()` handles NKey files or if a different `AuthHandler` method is needed. The jnats API may use `AuthHandler.buildFromNkey()` or similar.

- [ ] **Step 3: Run all tests**

Run: `mvn test -q`
Expected: 21 tests PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/flowable/eventregistry/spring/nats/config/NatsProperties.java \
        src/main/java/org/flowable/eventregistry/spring/nats/config/NatsChannelAutoConfiguration.java
git commit -m "feat: add NKey auth support to NatsProperties and auto-configuration"
```

---

### Task 5: NatsChannelMetrics (TDD)

**Files:**
- Create: `src/test/java/org/flowable/eventregistry/spring/nats/metrics/NatsChannelMetricsTest.java`
- Create: `src/main/java/org/flowable/eventregistry/spring/nats/metrics/NatsChannelMetrics.java`

- [ ] **Step 1: Write failing tests**

```java
package org.flowable.eventregistry.spring.nats.metrics;

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

        consume.increment();
        ack.increment();
        nak.increment();
        dlq.increment();

        assertThat(consume.count()).isEqualTo(1.0);
        assertThat(ack.count()).isEqualTo(1.0);
        assertThat(nak.count()).isEqualTo(1.0);
        assertThat(dlq.count()).isEqualTo(1.0);
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=NatsChannelMetricsTest -q`
Expected: FAIL — class does not exist

- [ ] **Step 3: Write implementation**

```java
package org.flowable.eventregistry.spring.nats.metrics;

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
                .tag("subject", subject).tag("channel", channel)
                .register(registry);
    }

    public Counter consumeErrorCount(String subject, String channel) {
        return Counter.builder("nats.inbound.errors")
                .tag("subject", subject).tag("channel", channel)
                .register(registry);
    }

    public Counter ackCount(String subject, String channel) {
        return Counter.builder("nats.jetstream.inbound.ack")
                .tag("subject", subject).tag("channel", channel)
                .register(registry);
    }

    public Counter nakCount(String subject, String channel) {
        return Counter.builder("nats.jetstream.inbound.nak")
                .tag("subject", subject).tag("channel", channel)
                .register(registry);
    }

    public Counter dlqCount(String subject, String channel) {
        return Counter.builder("nats.jetstream.inbound.dlq")
                .tag("subject", subject).tag("channel", channel)
                .register(registry);
    }

    public Counter publishCount(String subject, String channel) {
        return Counter.builder("nats.outbound.published")
                .tag("subject", subject).tag("channel", channel)
                .register(registry);
    }

    public Counter publishErrorCount(String subject, String channel) {
        return Counter.builder("nats.outbound.errors")
                .tag("subject", subject).tag("channel", channel)
                .register(registry);
    }

    public Counter jsPublishCount(String subject, String channel) {
        return Counter.builder("nats.jetstream.outbound.published")
                .tag("subject", subject).tag("channel", channel)
                .register(registry);
    }

    public Counter jsPublishErrorCount(String subject, String channel) {
        return Counter.builder("nats.jetstream.outbound.errors")
                .tag("subject", subject).tag("channel", channel)
                .register(registry);
    }

    public Timer processingTimer(String subject, String channel) {
        return Timer.builder("nats.inbound.processing.duration")
                .tag("subject", subject).tag("channel", channel)
                .register(registry);
    }

    // Connection-level metrics (no subject/channel tags)
    public Counter reconnectCount() {
        return Counter.builder("nats.connection.reconnects").register(registry);
    }

    public Counter slowConsumerCount() {
        return Counter.builder("nats.connection.slow.consumers").register(registry);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=NatsChannelMetricsTest -q`
Expected: 2 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/flowable/eventregistry/spring/nats/metrics/ \
        src/test/java/org/flowable/eventregistry/spring/nats/metrics/
git commit -m "feat: add NatsChannelMetrics with Micrometer counters and processing Timer"
```

---

### Task 6: Channel Model Field Additions

**Files:**
- Modify: `src/main/java/org/flowable/eventregistry/spring/nats/channel/NatsInboundChannelModel.java`
- Modify: `src/main/java/org/flowable/eventregistry/spring/nats/channel/NatsOutboundChannelModel.java`

- [ ] **Step 1: Add JetStream fields to NatsInboundChannelModel**

Add after existing fields:

```java
private String durableName;
private String deliverPolicy = "all";
private java.time.Duration ackWait = java.time.Duration.ofSeconds(30);
private int maxDeliver = 5;
private String dlqSubject;
private boolean autoCreateStream;
private String streamName;

// Getters and setters for all 7 new fields
```

The `dlqSubject` default should be computed at registration time if null: `"dlq." + subject`.

- [ ] **Step 2: Add JetStream fields to NatsOutboundChannelModel**

Add after existing fields:

```java
private boolean autoCreateStream;
private String streamName;

// Getters and setters for both
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/flowable/eventregistry/spring/nats/channel/
git commit -m "feat: add JetStream fields to channel models (durableName, deliverPolicy, ackWait, maxDeliver, dlqSubject, autoCreateStream, streamName)"
```

---

### Task 7: JetStreamStreamManager (TDD)

**Files:**
- Create: `src/test/java/org/flowable/eventregistry/spring/nats/jetstream/JetStreamStreamManagerTest.java`
- Create: `src/main/java/org/flowable/eventregistry/spring/nats/jetstream/JetStreamStreamManager.java`

- [ ] **Step 1: Write failing tests**

```java
package org.flowable.eventregistry.spring.nats.jetstream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.StreamInfo;
import org.flowable.common.engine.api.FlowableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JetStreamStreamManagerTest {

    private Connection connection;
    private JetStreamManagement jsm;
    private JetStreamStreamManager manager;

    @BeforeEach
    void setUp() throws Exception {
        connection = mock(Connection.class);
        jsm = mock(JetStreamManagement.class);
        when(connection.jetStreamManagement()).thenReturn(jsm);
        manager = new JetStreamStreamManager();
    }

    @Test
    void ensureStream_exists_noAction() throws Exception {
        when(jsm.getStreamInfo("test-stream")).thenReturn(mock(StreamInfo.class));

        manager.ensureStream("test-stream", "test.subject", connection);

        verify(jsm, never()).addStream(any());
    }

    @Test
    void ensureStream_notFound_creates() throws Exception {
        when(jsm.getStreamInfo("test-stream"))
                .thenThrow(new JetStreamApiException(404, "not found", "stream not found"));
        when(jsm.addStream(any(StreamConfiguration.class))).thenReturn(mock(StreamInfo.class));

        manager.ensureStream("test-stream", "test.subject", connection);

        verify(jsm).addStream(any(StreamConfiguration.class));
    }

    @Test
    void ensureStream_apiFails_throwsFlowableException() throws Exception {
        when(jsm.getStreamInfo("test-stream"))
                .thenThrow(new JetStreamApiException(500, "server error", "internal error"));

        assertThatThrownBy(() -> manager.ensureStream("test-stream", "test.subject", connection))
                .isInstanceOf(FlowableException.class)
                .hasMessageContaining("test-stream");
    }
}
```

Note: `JetStreamApiException` constructor may differ — check jnats API at compile time. The 3-arg constructor may be `(int errorCode, String errorDescription, String message)` or similar.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=JetStreamStreamManagerTest -q`
Expected: FAIL

- [ ] **Step 3: Write implementation**

Follow the spec Section 5.1 exactly — includes IOException and generic Exception handling.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=JetStreamStreamManagerTest -q`
Expected: 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/flowable/eventregistry/spring/nats/jetstream/JetStreamStreamManager.java \
        src/test/java/org/flowable/eventregistry/spring/nats/jetstream/JetStreamStreamManagerTest.java
git commit -m "feat: add JetStreamStreamManager with auto-create stream guard"
```

---

### Task 8: JetStreamOutboundEventChannelAdapter (TDD)

**Files:**
- Create: `src/test/java/org/flowable/eventregistry/spring/nats/jetstream/JetStreamOutboundEventChannelAdapterTest.java`
- Create: `src/main/java/org/flowable/eventregistry/spring/nats/jetstream/JetStreamOutboundEventChannelAdapter.java`

- [ ] **Step 1: Write failing tests**

3 test cases per spec:
- `sendEvent_publishesToJetStream` — verifies sync publish with correct subject and data
- `sendEvent_propagatesHeaders` — verifies headerMap is converted to NATS Headers
- `sendEvent_streamNotFound_throwsFlowableException` — verifies JetStreamApiException is wrapped

Use mock `JetStream`, verify `publish(NatsMessage)` is called with correct arguments.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=JetStreamOutboundEventChannelAdapterTest -q`
Expected: FAIL

- [ ] **Step 3: Write implementation**

Follow spec Section 3.2 exactly. Use `NatsHeaderUtils.toNatsHeaders()` for header conversion. Constructor takes `JetStream`, `String subject`, `NatsChannelMetrics` (nullable), `String channelKey`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=JetStreamOutboundEventChannelAdapterTest -q`
Expected: 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/flowable/eventregistry/spring/nats/jetstream/JetStreamOutboundEventChannelAdapter.java \
        src/test/java/org/flowable/eventregistry/spring/nats/jetstream/JetStreamOutboundEventChannelAdapterTest.java
git commit -m "feat: add JetStreamOutboundEventChannelAdapter with header propagation"
```

---

### Task 9: JetStreamInboundEventChannelAdapter (TDD)

This is the most complex class. 10 test cases.

**Files:**
- Create: `src/test/java/org/flowable/eventregistry/spring/nats/jetstream/JetStreamInboundEventChannelAdapterTest.java`
- Create: `src/main/java/org/flowable/eventregistry/spring/nats/jetstream/JetStreamInboundEventChannelAdapter.java`

- [ ] **Step 1: Write failing tests**

10 test cases per spec:
1. `handleMessage_success_acksMessage`
2. `handleMessage_error_naksWithDelay`
3. `handleMessage_error_backoffExponential` — verify backoff increases: delivery 1→1s, 2→2s, 3→4s
4. `handleMessage_error_metadataFails_fallsBackToPlainNak`
5. `handleMessage_maxDeliverExceeded_publishesToDlq`
6. `handleMessage_dlqJetStreamFails_fallbackToCoreNats`
7. `handleMessage_dlqBothFail_stillAcks`
8. `handleMessage_emptyBody_acksAndSkips`
9. `handleMessage_dlqDisabled_acksWithoutPublish`
10. `handleMessage_propagatesTraceIdToMdc`

For each test, mock `Message` with `metaData()` returning mock `JetStreamMetaData` with `numDelivered()`. Mock `JetStream` for DLQ publish, `Connection` for fallback publish.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=JetStreamInboundEventChannelAdapterTest -q`
Expected: FAIL

- [ ] **Step 3: Write implementation**

Follow spec Section 2.3 exactly. Key aspects:
- Constructor: `Connection`, `JetStream`, `String subject`, `int maxDeliver`, `String dlqSubject`, `NatsChannelMetrics` (nullable), `String channelKey`
- `subscribe()`: creates dispatcher, uses `VirtualThreadPerTaskExecutor` for offloading
- `handleMessage()`: full ack/nack/DLQ logic per spec
- `unsubscribe()`: drains dispatcher, closes executor
- `calculateBackoff()`: exponential with 30s cap

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=JetStreamInboundEventChannelAdapterTest -q`
Expected: 10 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/flowable/eventregistry/spring/nats/jetstream/JetStreamInboundEventChannelAdapter.java \
        src/test/java/org/flowable/eventregistry/spring/nats/jetstream/JetStreamInboundEventChannelAdapterTest.java
git commit -m "feat: add JetStreamInboundEventChannelAdapter with ack/nack, DLQ, virtual thread offloading"
```

---

### Task 10: NatsChannelDefinitionProcessor Update (TDD)

**Files:**
- Modify: `src/main/java/org/flowable/eventregistry/spring/nats/NatsChannelDefinitionProcessor.java`
- Modify: `src/test/java/org/flowable/eventregistry/spring/nats/NatsChannelDefinitionProcessorTest.java`

- [ ] **Step 1: Add 2 new test cases**

```java
@Test
void registerInbound_jetstreamTrue_createsJetStreamAdapter() {
    NatsInboundChannelModel model = new NatsInboundChannelModel();
    model.setKey("testChannel");
    model.setSubject("order.new");
    model.setJetstream(true);
    model.setMaxDeliver(5);

    processor.registerChannelModel(model, null, eventRegistry, eventRepositoryService, false);

    assertThat(model.getInboundEventChannelAdapter())
            .isInstanceOf(JetStreamInboundEventChannelAdapter.class);
}

@Test
void registerOutbound_jetstreamTrue_createsJetStreamAdapter() {
    NatsOutboundChannelModel model = new NatsOutboundChannelModel();
    model.setKey("testChannel");
    model.setSubject("order.completed");
    model.setJetstream(true);

    processor.registerChannelModel(model, null, eventRegistry, eventRepositoryService, false);

    assertThat(model.getOutboundEventChannelAdapter())
            .isInstanceOf(JetStreamOutboundEventChannelAdapter.class);
}
```

- [ ] **Step 2: Run tests to verify new tests fail**

Run: `mvn test -Dtest=NatsChannelDefinitionProcessorTest -q`
Expected: 2 new tests FAIL, 8 existing PASS

- [ ] **Step 3: Update processor**

- Add `JetStream` and `JetStreamStreamManager` and `NatsChannelMetrics` (nullable) as constructor dependencies
- Remove `validateJetstream()` method
- Add `registerJetStreamInbound()` and `registerJetStreamOutbound()` methods
- Update `registerChannelModel()` with if/else jetstream routing per spec Section 4.6
- In `registerJetStreamInbound()`: call `streamManager.ensureStream()` if `autoCreateStream=true`, create `JetStreamInboundEventChannelAdapter`, set defaults (dlqSubject defaults to `"dlq." + subject` if null)
- In `registerJetStreamOutbound()`: call `streamManager.ensureStream()` if `autoCreateStream=true`, create `JetStreamOutboundEventChannelAdapter`
- Consumer config: set server-side `maxDeliver = -1` per spec Section 4.7

- [ ] **Step 4: Run all tests**

Run: `mvn test -Dtest=NatsChannelDefinitionProcessorTest -q`
Expected: 10 tests PASS (8 existing + 2 new). The old `registerInbound_jetstreamTrue_throwsException` test must be REMOVED or updated since jetstream=true is now valid.

Actually, review the existing tests: `registerInbound_jetstreamTrue_throwsException` should be **replaced** by `registerInbound_jetstreamTrue_createsJetStreamAdapter`. Net: 8 - 1 + 2 = 9 tests. But the processor test also needs the JetStream mock in setUp(). Update `setUp()` to provide mock `JetStream`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/flowable/eventregistry/spring/nats/NatsChannelDefinitionProcessor.java \
        src/test/java/org/flowable/eventregistry/spring/nats/NatsChannelDefinitionProcessorTest.java
git commit -m "feat: activate JetStream routing in NatsChannelDefinitionProcessor"
```

---

### Task 11: NatsChannelAutoConfiguration Update

**Files:**
- Modify: `src/main/java/org/flowable/eventregistry/spring/nats/config/NatsChannelAutoConfiguration.java`
- Modify: `src/test/java/org/flowable/eventregistry/spring/nats/config/NatsChannelAutoConfigurationTest.java`

- [ ] **Step 1: Add JetStream and Metrics beans**

Add to auto-configuration class:

```java
@Bean
@ConditionalOnMissingBean
public JetStream natsJetStream(Connection connection) throws IOException {
    return connection.jetStream();
}

@Bean
@ConditionalOnMissingBean
public JetStreamStreamManager jetStreamStreamManager() {
    return new JetStreamStreamManager();
}

@Bean
@ConditionalOnMissingBean
@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
public NatsChannelMetrics natsChannelMetrics(MeterRegistry registry) {
    return new NatsChannelMetrics(registry);
}
```

Update `natsChannelDefinitionProcessor` bean to inject new dependencies:

```java
@Bean
@ConditionalOnMissingBean
public NatsChannelDefinitionProcessor natsChannelDefinitionProcessor(
        Connection connection,
        JetStream jetStream,
        JetStreamStreamManager streamManager,
        @Autowired(required = false) NatsChannelMetrics metrics) {
    return new NatsChannelDefinitionProcessor(connection, jetStream, streamManager, metrics);
}
```

- [ ] **Step 2: Update auto-config test**

Update `MockConnectionConfig` to provide mock JetStream bean. Add test verifying JetStream bean is created.

- [ ] **Step 3: Run all tests**

Run: `mvn test -q`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/flowable/eventregistry/spring/nats/config/NatsChannelAutoConfiguration.java \
        src/test/java/org/flowable/eventregistry/spring/nats/config/NatsChannelAutoConfigurationTest.java
git commit -m "feat: add JetStream, StreamManager, and Metrics beans to auto-configuration"
```

---

### Task 12: JetStream Integration Tests

**Files:**
- Create: `src/test/java/org/flowable/eventregistry/spring/nats/jetstream/JetStreamInboundIntegrationTest.java`
- Create: `src/test/java/org/flowable/eventregistry/spring/nats/jetstream/JetStreamOutboundIntegrationTest.java`
- Create: `src/test/java/org/flowable/eventregistry/spring/nats/jetstream/JetStreamStreamManagerIntegrationTest.java`

All use `nats:2.10-alpine` with `--jetstream` flag.

- [ ] **Step 1: Create JetStreamInboundIntegrationTest (3 cases)**

1. `inbound_receivesAndAcks` — publish to JetStream stream → adapter consumes → ack → message not re-delivered
2. `inbound_processingError_redelivers` — EventRegistryStub throws on first call → nak → re-delivered → succeeds on second call
3. `inbound_maxDeliverExceeded_sentToDlq` — EventRegistryStub always throws → after maxDeliver attempts → message appears on DLQ subject

Key: create stream first via `JetStreamManagement`, then publish via `JetStream`. Subscribe DLQ subject to capture DLQ messages. Use `Awaitility` for async assertions.

- [ ] **Step 2: Create JetStreamOutboundIntegrationTest (1 case)**

1. `outbound_publishesToStream` — create stream → adapter publish → verify message in stream via `JetStream.subscribe()`

- [ ] **Step 3: Create JetStreamStreamManagerIntegrationTest (1 case)**

1. `autoCreateStream_createsAndVerifies` — stream doesn't exist → `ensureStream()` → `getStreamInfo()` succeeds

- [ ] **Step 4: Run integration tests**

Run: `mvn test -Dtest="JetStream*IntegrationTest" -q`
Expected: 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/test/java/org/flowable/eventregistry/spring/nats/jetstream/JetStreamInboundIntegrationTest.java \
        src/test/java/org/flowable/eventregistry/spring/nats/jetstream/JetStreamOutboundIntegrationTest.java \
        src/test/java/org/flowable/eventregistry/spring/nats/jetstream/JetStreamStreamManagerIntegrationTest.java
git commit -m "test: add JetStream integration tests with Testcontainers"
```

---

### Task 13: Full Test Suite Verification

**Files:** None (verification only)

- [ ] **Step 1: Run all tests**

Run: `mvn test`
Expected: 46 tests PASS (21 Phase 1 + 25 Phase 2), BUILD SUCCESS

- [ ] **Step 2: Verify test counts**

Run: `mvn test 2>&1 | grep -E "Tests run:"`

Expected breakdown:
```
NatsChannelDefinitionProcessorTest:            9  (was 8, +2 new, -1 removed)
NatsInboundEventChannelAdapterTest:            5  (unchanged)
NatsOutboundEventChannelAdapterTest:           2  (unchanged)
NatsChannelAutoConfigurationTest:              3+ (may have additional)
NatsInboundChannelIntegrationTest:             2  (unchanged)
NatsOutboundChannelIntegrationTest:            1  (unchanged)
NatsChannelMetricsTest:                        2  (new)
JetStreamInboundEventChannelAdapterTest:      10  (new)
JetStreamOutboundEventChannelAdapterTest:      3  (new)
JetStreamStreamManagerTest:                    3  (new)
JetStreamInboundIntegrationTest:               3  (new)
JetStreamOutboundIntegrationTest:              1  (new)
JetStreamStreamManagerIntegrationTest:         1  (new)
```

- [ ] **Step 3: Verify file structure**

Run: `find src -name "*.java" | sort`

Expected new files:
```
src/main/java/.../NatsHeaderUtils.java
src/main/java/.../jetstream/JetStreamInboundEventChannelAdapter.java
src/main/java/.../jetstream/JetStreamOutboundEventChannelAdapter.java
src/main/java/.../jetstream/JetStreamStreamManager.java
src/main/java/.../metrics/NatsChannelMetrics.java
src/test/java/.../jetstream/JetStreamInboundEventChannelAdapterTest.java
src/test/java/.../jetstream/JetStreamInboundIntegrationTest.java
src/test/java/.../jetstream/JetStreamOutboundEventChannelAdapterTest.java
src/test/java/.../jetstream/JetStreamOutboundIntegrationTest.java
src/test/java/.../jetstream/JetStreamStreamManagerIntegrationTest.java
src/test/java/.../jetstream/JetStreamStreamManagerTest.java
src/test/java/.../metrics/NatsChannelMetricsTest.java
```

- [ ] **Step 4: Commit if any adjustments needed**

```bash
git add -A
git commit -m "chore: Phase 2 complete — all 46 tests passing"
```
