# Phase 2: Flowable NATS Channel — JetStream Adapter Design Spec

## Overview

JetStream support for the Flowable NATS channel adapter. Extends Phase 1's Core NATS pub/sub with persistent messaging, ack/nack semantics, dead letter queue routing, Micrometer metrics, and structured logging compliance.

**Scope:** JetStream push-based consumers, sync publish on virtual threads, adapter-managed DLQ (JetStream-persistent with Core NATS fallback), Micrometer metrics + Timer, NKey auth, structured logging + MDC trace propagation, inbound virtual thread offloading, nakWithDelay exponential backoff, outbound header propagation.

**Prerequisites:**
- Phase 1 complete (8 production classes, 21 tests passing)
- Java 21+ (virtual threads)
- `spring.threads.virtual.enabled: true`
- NATS 2.10+ (required for `nakWithDelay()`)

**Infrastructure Note:** Upgrading from Java 17 (Phase 1) to Java 21 requires updating CI/CD pipelines, Docker base images, and deployment configurations.

**License:** Apache 2.0

---

## 1. Class Structure

6 new production classes + modifications to 5 existing Phase 1 classes.

### 1.1 New Classes

```
src/main/java/org/flowable/eventregistry/spring/nats/
├── jetstream/
│   ├── JetStreamInboundEventChannelAdapter.java     # Push consumer, ack/nack, DLQ routing
│   ├── JetStreamOutboundEventChannelAdapter.java    # Sync publish (virtual thread safe)
│   └── JetStreamStreamManager.java                  # Auto-create stream guard
├── metrics/
│   └── NatsChannelMetrics.java                      # Micrometer counter registry
```

### 1.2 Modified Phase 1 Classes

| Class | Change |
|-------|--------|
| `NatsInboundChannelModel` | Add JetStream fields (durableName, deliverPolicy, ackWait, maxDeliver, dlqSubject, autoCreateStream, streamName) |
| `NatsOutboundChannelModel` | Add JetStream fields (autoCreateStream, streamName) |
| `NatsChannelDefinitionProcessor` | Activate jetstream branch — route to JetStream adapters |
| `NatsChannelAutoConfiguration` | Add JetStream, Metrics beans; NKey auth support |
| `NatsProperties` | Add nkeyFile field |

### 1.3 Structured Logging Retrofit (Phase 1 classes)

All existing log statements in Phase 1 classes updated to use `StructuredArguments.kv()` format per OBSERVABILITY_GUIDELINE.

| Class | Log Statements Affected |
|-------|------------------------|
| `NatsInboundEventChannelAdapter` | subscribe, unsubscribe, handleMessage (empty/error) |
| `NatsOutboundEventChannelAdapter` | sendEvent error |
| `NatsChannelAutoConfiguration` | ConnectionListener, ErrorListener |

### 1.4 New Test Classes

```
src/test/java/org/flowable/eventregistry/spring/nats/
├── jetstream/
│   ├── JetStreamInboundEventChannelAdapterTest.java     # Unit: 10 cases
│   ├── JetStreamOutboundEventChannelAdapterTest.java    # Unit: 3 cases
│   ├── JetStreamStreamManagerTest.java                  # Unit: 3 cases
│   ├── JetStreamInboundIntegrationTest.java             # Integration: 3 cases
│   ├── JetStreamOutboundIntegrationTest.java            # Integration: 1 case
│   └── JetStreamStreamManagerIntegrationTest.java       # Integration: 1 case
├── metrics/
│   └── NatsChannelMetricsTest.java                      # Unit: 2 cases
```

Plus 2 additional cases in existing `NatsChannelDefinitionProcessorTest`.

---

## 2. JetStream Inbound Data Flow (NATS → Flowable)

### 2.1 Flow Diagram

```
┌─────────────┐     ┌─────────────────────────────────────┐     ┌──────────────────┐
│ JetStream    │────▶│ JetStreamInboundEventChannelAdapter  │────▶│ Flowable Event   │
│ Stream       │     │                                     │     │ Registry Engine   │
│              │     │ 1. Push delivery (MessageHandler)   │     │                  │
│ subject:     │     │ 2. MDC trace_id propagation         │     │ ● deserialize    │
│ "order.new"  │     │ 3. Wrap → NatsInboundEvent          │     │ ● correlate      │
│              │     │ 4. eventRegistry.eventReceived()    │     │ ● trigger process│
│ consumer:    │     │ 5a. success → msg.ack()             │     │                  │
│ "order-svc"  │     │ 5b. exception → msg.nak()           │     │                  │
│              │     │ 5c. maxDeliver → DLQ publish + ack  │     │                  │
└─────────────┘     └─────────────────────────────────────┘     └──────────────────┘
```

### 2.2 Processing Steps

| # | Where | What Happens |
|---|-------|-------------|
| 1 | `NatsChannelDefinitionProcessor` | `jetstream=true` → creates `JetStreamInboundEventChannelAdapter` |
| 2 | `JetStreamStreamManager` | If `autoCreateStream=true` and stream doesn't exist → create it |
| 3 | Adapter | `jetStream.subscribe(subject, consumerConfig)` → push-based subscription |
| 4 | Message arrives | `MessageHandler` callback fires on NATS dispatcher thread |
| 5 | Adapter | **Offload to virtual thread** via `VirtualThreadPerTaskExecutor` (NATS dispatcher stays free) |
| 6 | Virtual thread | Extract `X-Trace-Id` header → `MDC.put("trace_id", ...)` |
| 7 | Virtual thread | Check `msg.metaData().numDelivered()` > maxDeliver → if yes, go to Step 10 |
| 8a | Success path | `eventRegistry.eventReceived()` succeeds → `msg.ack()` → `metrics.ackCount++` |
| 8b | Error path | Exception → `msg.nakWithDelay(backoff)` → JetStream re-delivers after delay → `metrics.nakCount++` |
| 9 | Finally | `MDC.remove("trace_id")` |
| 10 | DLQ path | `jetStream.publish(dlqSubject, msg.getData())` (persistent) → fallback `connection.publish()` → `msg.ack()` → `metrics.dlqCount++` |

### 2.2.1 Virtual Thread Offloading

NATS dispatcher threads are a limited shared resource. If `eventRegistry.eventReceived()` triggers DB operations or complex Flowable logic, blocking the dispatcher thread leads to slow consumer detection and message drops.

Solution: offload message processing to virtual threads immediately in the callback:

```java
private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

// In subscribe():
dispatcher.subscribe(subject, msg -> {
    executor.submit(() -> handleMessage(msg));
});
```

This pattern mirrors Go's `go handleMessage(msg)` — the NATS dispatcher returns immediately and continues accepting messages, while processing runs on a virtual thread that doesn't block OS resources.

### 2.2.2 nakWithDelay Exponential Backoff

Using `msg.nak()` causes immediate re-delivery. If the error is persistent (e.g., database down for 1 minute), the message exhausts maxDeliver within seconds and lands in DLQ prematurely.

Solution: use `nakWithDelay()` (NATS 2.10+) with exponential backoff:

```java
private Duration calculateBackoff(long deliveryCount) {
    long seconds = Math.min((long) Math.pow(2, deliveryCount - 1), 30);  // 1s, 2s, 4s, 8s, 16s, 30s cap
    return Duration.ofSeconds(seconds);
}
```

This is compliant with ERROR_HANDLING_GUIDELINE Section 4.1 (Retry with Exponential Backoff).

### 2.3 Ack/Nack Implementation

```java
void handleMessage(Message msg) {
    String traceId = extractHeader(msg, "X-Trace-Id");
    try {
        if (traceId != null) {
            MDC.put("trace_id", traceId);
        }

        long deliveryCount = msg.metaData().numDelivered();

        if (deliveryCount > maxDeliver) {
            publishToDlq(msg);
            msg.ack();
            metrics.dlqCount(subject, channelKey).increment();
            log.warn("Message sent to DLQ",
                    kv("channel", channelKey),
                    kv("subject", subject),
                    kv("dlq_subject", dlqSubject),
                    kv("delivery_count", deliveryCount));
            return;
        }

        if (msg.getData() == null || msg.getData().length == 0) {
            log.warn("Empty message received, skipping",
                    kv("channel", channelKey),
                    kv("subject", msg.getSubject()));
            msg.ack();
            return;
        }

        NatsInboundEvent event = new NatsInboundEvent(msg);
        Timer.Sample sample = Timer.start();
        eventRegistry.eventReceived(inboundChannelModel, event);
        sample.stop(metrics.processingTimer(subject, channelKey));
        msg.ack();
        metrics.consumeCount(subject, channelKey).increment();
        metrics.ackCount(subject, channelKey).increment();

    } catch (Exception e) {
        try {
            long deliveryCount = msg.metaData().numDelivered();
            Duration backoff = calculateBackoff(deliveryCount);
            msg.nakWithDelay(backoff);
            metrics.nakCount(subject, channelKey).increment();
            log.error("Message processing failed, retry after {}",
                    kv("backoff", backoff),
                    kv("channel", channelKey),
                    kv("subject", msg.getSubject()),
                    kv("delivery_count", deliveryCount),
                    e);
        } catch (Exception nakError) {
            // Defensive: if metaData() or nakWithDelay() fails, fall back to plain nak
            try { msg.nak(); } catch (Exception ignored) { /* best effort */ }
            log.error("Message processing failed and nak handling also failed",
                    kv("channel", channelKey),
                    e);
        }
    } finally {
        MDC.remove("trace_id");
    }
}

private Duration calculateBackoff(long deliveryCount) {
    long seconds = Math.min((long) Math.pow(2, deliveryCount - 1), 30);
    return Duration.ofSeconds(seconds);
}
```

### 2.4 DLQ Publish (JetStream-persistent with Core NATS fallback)

```java
private void publishToDlq(Message msg) {
    if (dlqSubject == null) {
        log.warn("DLQ disabled, dropping poison message",
                kv("channel", channelKey),
                kv("subject", msg.getSubject()));
        return;
    }
    try {
        // Primary: JetStream publish — guaranteed persistence (virtual thread safe)
        jetStream.publish(dlqSubject, msg.getData());
    } catch (Exception e) {
        log.warn("DLQ JetStream publish failed, falling back to Core NATS",
                kv("channel", channelKey),
                kv("dlq_subject", dlqSubject),
                e);
        try {
            // Fallback: Core NATS — best-effort delivery
            connection.publish(dlqSubject, msg.getData());
        } catch (Exception e2) {
            log.error("DLQ publish failed completely, message dropped to prevent infinite loop",
                    kv("channel", channelKey),
                    kv("dlq_subject", dlqSubject),
                    e2);
        }
    }
}
```

**DLQ publish strategy:**
1. **Primary:** `jetStream.publish()` — persistent, server-acked. Since we're on a virtual thread, the sync call doesn't block OS resources.
2. **Fallback:** `connection.publish()` (Core NATS) — if JetStream publish fails (e.g., DLQ stream doesn't exist), best-effort delivery.
3. **Last resort:** Log error, drop message. A poison message must never cause an infinite re-delivery loop.

**Critical:** `msg.ack()` is called regardless of DLQ publish success (in the caller method). This invariant is non-negotiable.

---

## 3. JetStream Outbound Data Flow (Flowable → NATS)

### 3.1 Flow Diagram

```
┌──────────────────┐     ┌──────────────────────────────────────┐     ┌─────────────┐
│ Flowable Engine   │────▶│ JetStreamOutboundEventChannelAdapter  │────▶│ JetStream   │
│                  │     │                                      │     │ Stream      │
│ ● process sends  │     │ 1. jetStream.publish(subject, data) │     │             │
│   event          │     │    (sync — virtual thread safe)     │     │ subject:    │
│                  │     │ 2. PublishAck returned               │     │ "order.done"│
│                  │     │ 3. metrics.publishCount++            │     │             │
└──────────────────┘     └──────────────────────────────────────┘     └─────────────┘
```

### 3.2 Implementation

```java
public class JetStreamOutboundEventChannelAdapter implements OutboundEventChannelAdapter<String> {

    private final JetStream jetStream;
    private final String subject;
    private final NatsChannelMetrics metrics;
    private final String channelKey;

    @Override
    public void sendEvent(String rawEvent, Map<String, Object> headerMap) {
        try {
            byte[] data = rawEvent.getBytes(StandardCharsets.UTF_8);
            NatsMessage message = NatsMessage.builder()
                    .subject(subject)
                    .data(data)
                    .headers(toNatsHeaders(headerMap))
                    .build();
            PublishAck ack = jetStream.publish(message);
            metrics.jsPublishCount(subject, channelKey).increment();
            log.debug("Published to JetStream",
                    kv("channel", channelKey),
                    kv("subject", subject),
                    kv("stream_seq", ack.getSeqno()));
        } catch (Exception e) {
            metrics.jsPublishErrorCount(subject, channelKey).increment();
            log.error("JetStream publish failed",
                    kv("channel", channelKey),
                    kv("subject", subject),
                    e);
            throw new FlowableException(
                    "JetStream publish failed for channel '" + channelKey
                    + "' on subject '" + subject + "'", e);
        }
    }

    private Headers toNatsHeaders(Map<String, Object> headerMap) {
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

### 3.3 Header Propagation

Flowable's `OutboundEventChannelAdapter.sendEvent(T rawEvent, Map<String, Object> headerMap)` provides a header map that may contain trace/correlation IDs, content type, and other metadata. These MUST be propagated to the NATS message for end-to-end traceability.

Headers are converted from `Map<String, Object>` to NATS `Headers` via `toNatsHeaders()`. This enables:
- `X-Trace-Id` → flows from Flowable process through NATS to downstream consumers
- `X-Correlation-Id` → correlates events across service boundaries
- `Content-Type` → signals serialization format

**Phase 1 retrofit:** The same header propagation must be added to `NatsOutboundEventChannelAdapter` (Core NATS):

```java
// NatsOutboundEventChannelAdapter.sendEvent() — updated
public void sendEvent(String rawEvent, Map<String, Object> headerMap) {
    // ... status check ...
    NatsMessage message = NatsMessage.builder()
            .subject(subject)
            .data(rawEvent.getBytes(StandardCharsets.UTF_8))
            .headers(toNatsHeaders(headerMap))
            .build();
    connection.publish(message);
}
```

The `toNatsHeaders()` helper is shared between Core NATS and JetStream outbound adapters (extract to a utility method or common base).

### 3.4 Virtual Thread Requirement

`jetStream.publish()` is a blocking call that waits for server ack. On a virtual thread, the carrier thread is released during I/O wait — no OS resource is blocked. This requires:
- Java 21+
- `spring.threads.virtual.enabled: true` in application configuration

On error, `FlowableException` is thrown — the Flowable process knows the publish failed and can handle it via its own error handling pipeline.

---

## 4. Channel Model Fields & JSON Contracts

### 4.1 NatsInboundChannelModel (JetStream additions)

| Field | Type | Default | Required | Description |
|-------|------|---------|----------|-------------|
| `subject` | String | — | Yes | NATS subscribe subject |
| `queueGroup` | String | null | No | Queue group (Core NATS only) |
| `jetstream` | boolean | false | No | Enable JetStream mode |
| `durableName` | String | null | No | Durable consumer name. null = ephemeral |
| `deliverPolicy` | String | `"all"` | No | `all`, `last`, `new`, `byStartSequence`, `byStartTime` |
| `ackWait` | Duration | `30s` | No | Ack timeout before re-delivery |
| `maxDeliver` | int | `5` | No | Max re-delivery attempts before DLQ |
| `dlqSubject` | String | `"dlq.{subject}"` | No | DLQ subject. null = DLQ disabled |
| `autoCreateStream` | boolean | false | No | Create stream if not exists |
| `streamName` | String | null | No | Target stream name (for auto-create) |

### 4.2 NatsOutboundChannelModel (JetStream additions)

| Field | Type | Default | Required | Description |
|-------|------|---------|----------|-------------|
| `subject` | String | — | Yes | NATS publish subject |
| `jetstream` | boolean | false | No | Enable JetStream mode |
| `autoCreateStream` | boolean | false | No | Create stream if not exists |
| `streamName` | String | null | No | Target stream name (for auto-create) |

### 4.3 Inbound Channel JSON Example (JetStream)

```json
{
  "key": "orderInboundChannel",
  "category": "channel",
  "name": "Order Inbound Channel",
  "channelType": "inbound",
  "type": "nats",
  "deserializerType": "json",
  "channelEventKeyDetection": {
    "fixedValue": "orderEvent"
  },
  "channelFields": [
    { "name": "subject", "stringValue": "order.new" },
    { "name": "jetstream", "stringValue": "true" },
    { "name": "durableName", "stringValue": "order-consumer" },
    { "name": "deliverPolicy", "stringValue": "new" },
    { "name": "maxDeliver", "stringValue": "5" },
    { "name": "ackWait", "stringValue": "30s" },
    { "name": "dlqSubject", "stringValue": "dlq.order.new" },
    { "name": "autoCreateStream", "stringValue": "false" }
  ]
}
```

### 4.4 Outbound Channel JSON Example (JetStream)

```json
{
  "key": "orderOutboundChannel",
  "category": "channel",
  "name": "Order Outbound Channel",
  "channelType": "outbound",
  "type": "nats",
  "serializerType": "json",
  "channelFields": [
    { "name": "subject", "stringValue": "order.completed" },
    { "name": "jetstream", "stringValue": "true" },
    { "name": "autoCreateStream", "stringValue": "false" }
  ]
}
```

### 4.5 Channel Field Parsing

Flowable delivers channel JSON as a `ChannelModel` with a generic `channelFields` list. The processor must parse these fields into typed model objects. This happens in `registerChannelModel()` before routing:

```java
private NatsInboundChannelModel parseInboundFields(NatsInboundChannelModel model) {
    // channelFields are already on the model via Flowable's JSON deserialization
    // For JetStream fields, parse from the model's extension or channelFields:
    // The exact mechanism depends on how Flowable populates custom model subclasses.
    //
    // Option A: Flowable uses Jackson to deserialize directly into our model subclass
    //           (if registered via ChannelModelClassProvider or similar SPI)
    // Option B: Fields are in ChannelModel.getExtension() JsonNode and we parse manually
    //
    // Implementation must verify at compile time which mechanism Flowable 7.1.0 uses.
    // The key requirement: all JetStream fields (durableName, deliverPolicy, ackWait,
    // maxDeliver, dlqSubject, autoCreateStream, streamName) must be populated on
    // the model object before the adapter is created.
    return model;
}
```

**Implementation note:** The exact parsing mechanism must be verified against Flowable 7.1.0 source at implementation time. The Kafka adapter uses `KafkaInboundChannelModel` with Jackson `@JsonProperty` annotations — our approach should mirror this pattern.

### 4.6 Processor Routing (Updated)

```java
// NatsChannelDefinitionProcessor — validateJetstream() removed, replaced with routing
if (channelModel instanceof NatsInboundChannelModel inboundModel) {
    if (inboundModel.isJetstream()) {
        registerJetStreamInbound(inboundModel, tenantId, eventRegistry);
    } else {
        registerInbound(inboundModel, tenantId, eventRegistry);  // Phase 1 path — unchanged
    }
} else if (channelModel instanceof NatsOutboundChannelModel outboundModel) {
    if (outboundModel.isJetstream()) {
        registerJetStreamOutbound(outboundModel);
    } else {
        registerOutbound(outboundModel);  // Phase 1 path — unchanged
    }
}
```

Phase 1 code paths are fully preserved. `jetstream=false` (default) behaves identically to Phase 1.

### 4.7 JetStream Consumer Config: Server-side maxDeliver

**Critical:** The adapter manages DLQ routing based on its own `maxDeliver` config. To prevent conflict with JetStream server-side maxDeliver enforcement, the consumer subscription must set server-side `maxDeliver = -1` (unlimited):

```java
ConsumerConfiguration config = ConsumerConfiguration.builder()
        .durable(durableName)
        .deliverPolicy(deliverPolicy)
        .ackWait(ackWait)
        .maxDeliver(-1)  // unlimited — adapter manages DLQ routing
        .build();
```

The adapter checks `numDelivered() > maxDeliver` and routes to DLQ. If server-side maxDeliver were set to the same value, the server would stop delivery before the adapter's DLQ logic triggers (server stops at count N, adapter checks for N+1). Setting server to -1 gives the adapter full control.

---

## 5. Stream Auto-Create Guard

### 5.1 JetStreamStreamManager

```java
public class JetStreamStreamManager {

    public void ensureStream(String streamName, String subject, Connection connection) {
        try {
            JetStreamManagement jsm = connection.jetStreamManagement();

            try {
                jsm.getStreamInfo(streamName);
                log.debug("Stream exists",
                        kv("stream", streamName));
            } catch (JetStreamApiException e) {
                if (e.getErrorCode() == 404) {
                    StreamConfiguration config = StreamConfiguration.builder()
                            .name(streamName)
                            .subjects(subject)
                            .retentionPolicy(RetentionPolicy.Limits)
                            .storageType(StorageType.File)
                            .build();
                    jsm.addStream(config);
                    log.info("Stream created",
                            kv("stream", streamName),
                            kv("subject", subject));
                } else {
                    throw new FlowableException(
                            "Failed to check stream '" + streamName + "'", e);
                }
            }
        } catch (IOException e) {
            throw new FlowableException(
                    "I/O error while managing stream '" + streamName + "'", e);
        } catch (FlowableException e) {
            throw e;  // pass through
        } catch (Exception e) {
            throw new FlowableException(
                    "Unexpected error managing stream '" + streamName + "'", e);
        }
    }
}
```

- Default: `autoCreateStream=false` — stream must be pre-created by ops
- `autoCreateStream=true` — creates with minimal defaults (Limits retention, File storage)
- Production environments should pre-create streams with proper configuration

---

## 6. Micrometer Metrics

### 6.1 NatsChannelMetrics

```java
public class NatsChannelMetrics {

    private final MeterRegistry registry;

    // ── Counters ──

    // Inbound (Core NATS + JetStream)
    // nats.inbound.consumed{subject, channel}
    // nats.inbound.errors{subject, channel}

    // JetStream Inbound
    // nats.jetstream.inbound.ack{subject, channel}
    // nats.jetstream.inbound.nak{subject, channel}
    // nats.jetstream.inbound.dlq{subject, channel}

    // Outbound (Core NATS)
    // nats.outbound.published{subject, channel}
    // nats.outbound.errors{subject, channel}

    // JetStream Outbound
    // nats.jetstream.outbound.published{subject, channel}
    // nats.jetstream.outbound.errors{subject, channel}

    // Connection
    // nats.connection.reconnects{}
    // nats.connection.slow.consumers{}

    // ── Timer ──

    // nats.inbound.processing.duration{subject, channel}
    //   → Measures eventRegistry.eventReceived() execution time
    //   → Enables P95/P99 monitoring for bottleneck detection
    //   → Prometheus histogram buckets: [0.001, 0.005, 0.01, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10]
}
```

**Naming:** Micrometer dot-notation → auto-converted to Prometheus snake_case with `_total` suffix for counters and `_seconds` for timers. Compliant with OBSERVABILITY_GUIDELINE metric naming convention.

**Labels:** `subject` and `channel` — 2 labels, well within the max 5 label rule. Both are bounded values.

**Timer usage:**
```java
Timer.Sample sample = Timer.start();
eventRegistry.eventReceived(inboundChannelModel, event);
sample.stop(metrics.processingTimer(subject, channelKey));
```

This enables Grafana dashboards to show P95/P99 processing latency, helping identify slow Flowable logic or DB bottlenecks before they trigger slow consumer alerts.

### 6.2 JetStream Bean and Conditional Loading

```java
// NatsChannelAutoConfiguration — Phase 2 additions

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
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnMissingBean
public NatsChannelMetrics natsChannelMetrics(MeterRegistry registry) {
    return new NatsChannelMetrics(registry);
}
```

The `JetStream` object is obtained from `Connection` and provided as a bean. The processor and adapters receive it via constructor injection.

When Micrometer is not on the classpath, no metrics bean is created. Adapters receive `NatsChannelMetrics` as an optional dependency — if null, no metrics are recorded. Zero overhead.

### 6.3 Executor Lifecycle

The `VirtualThreadPerTaskExecutor` in `JetStreamInboundEventChannelAdapter` must be shut down when the adapter unsubscribes:

```java
public void unsubscribe() {
    if (dispatcher != null) {
        // ... drain and close dispatcher ...
    }
    if (executor != null) {
        executor.close();  // waits for running virtual threads to complete
        executor = null;
    }
}
```

This prevents virtual thread leaks when a channel is undeployed.

### 6.3 Metrics Integration Points

| Component | Metrics Incremented |
|-----------|-------------------|
| `NatsInboundEventChannelAdapter` (Phase 1) | `consumeCount`, `consumeErrorCount` |
| `JetStreamInboundEventChannelAdapter` | `consumeCount`, `ackCount`, `nakCount`, `dlqCount` |
| `NatsOutboundEventChannelAdapter` (Phase 1) | `publishCount`, `publishErrorCount` |
| `JetStreamOutboundEventChannelAdapter` | `jsPublishCount`, `jsPublishErrorCount` |
| `NatsChannelAutoConfiguration` (ConnectionListener) | `reconnectCount`, `slowConsumerCount` |

---

## 7. NKey Auth Support

### 7.1 NatsProperties Addition

```java
private String nkeyFile;  // NKey seed file path
```

### 7.2 Auth Priority (Updated)

```
credentialsFile > nkeyFile > token > username/password
```

```java
private void configureAuth(Options.Builder builder, NatsProperties props) {
    if (props.getCredentialsFile() != null) {
        builder.authHandler(Nats.credentials(props.getCredentialsFile()));
    } else if (props.getNkeyFile() != null) {
        builder.authHandler(AuthHandler.fromFile(props.getNkeyFile()));
    } else if (props.getToken() != null) {
        builder.token(props.getToken().toCharArray());
    } else if (props.getUsername() != null) {
        builder.userInfo(props.getUsername(), props.getPassword());
    }
}
```

---

## 8. Structured Logging & MDC Trace Propagation

### 8.1 Structured Logging Standard

All log statements (new and existing) MUST use `StructuredArguments.kv()` format per OBSERVABILITY_GUIDELINE:

```java
import static net.logstash.logback.argument.StructuredArguments.kv;

// Correct
log.info("NATS connected",
        kv("host", conn.getServerInfo().getHost()));

// Wrong (Phase 1 style — to be retrofitted)
log.info("NATS connected: {}", conn.getServerInfo().getHost());
```

### 8.2 MDC Trace ID Propagation

Inbound adapters (both Core NATS and JetStream) extract `X-Trace-Id` from NATS message headers and place it in SLF4J MDC:

```java
void handleMessage(Message msg) {
    String traceId = extractHeader(msg, "X-Trace-Id");
    try {
        if (traceId != null) {
            MDC.put("trace_id", traceId);
        }
        // ... process message
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

This ensures `trace_id` appears in all log output during message processing, enabling cross-service correlation via Loki/Grafana.

### 8.3 Phase 1 Log Retrofit

Existing Phase 1 log statements to be updated:

| Class | Statements | Change |
|-------|-----------|--------|
| `NatsInboundEventChannelAdapter` | 4 (subscribe, unsubscribe, empty msg, error) | `kv()` format + MDC trace |
| `NatsOutboundEventChannelAdapter` | 1 (connection error) | `kv()` format |
| `NatsChannelAutoConfiguration` | 6 (connection/error listeners) | `kv()` format |

### 8.4 Log Level Compliance (ERROR_HANDLING_GUIDELINE)

| Event | Level | Rationale |
|-------|-------|-----------|
| Message consumed successfully | DEBUG | High frequency, diagnostics only |
| Empty message skipped | WARN | Degraded input, recoverable |
| Message sent to DLQ | WARN | Expected behavior after maxDeliver |
| Message processing failed (will retry) | ERROR | Requires engineering attention |
| DLQ publish failed | ERROR | Data loss scenario |
| Connection events (connected, closed) | INFO | Operational state change |
| Reconnected | WARN | Degraded state recovered |
| Slow consumer detected | WARN | Performance degradation |
| Stream created (auto-create) | INFO | Infrastructure state change |

---

## 9. Error Handling

### 9.1 JetStream Error Taxonomy

| Category | Error | Strategy |
|----------|-------|----------|
| **Consumer Registration** | Stream not found, `autoCreateStream=false` | `FlowableException` — channel deploy fails |
| | Stream not found, `autoCreateStream=true` | Create stream, then subscribe |
| | Stream creation failed | `FlowableException` — channel deploy fails |
| | Durable consumer config conflict | `FlowableException` — clear error message |
| **Inbound Processing** | Message processing error (within maxDeliver) | `msg.nakWithDelay(backoff)` → JetStream re-delivers after exponential delay |
| | maxDeliver exceeded | DLQ publish (JetStream primary, Core NATS fallback) → `msg.ack()` → metrics |
| | DLQ JetStream publish failed | Fallback to Core NATS `connection.publish()` |
| | DLQ Core NATS fallback also failed | `log.error` → `msg.ack()` (prevent infinite loop) |
| | ack/nak timeout | JetStream auto re-delivers (after ackWait) |
| **Outbound Publishing** | Stream does not exist | `JetStreamApiException` → `FlowableException` |
| | Connection unavailable | Same as Phase 1 (status check) |
| **Connection** | All Phase 1 errors | Unchanged (reconnect, slow consumer, etc.) |

### 9.2 Retry with Exponential Backoff (nakWithDelay)

Instead of `msg.nak()` (immediate re-delivery), we use `msg.nakWithDelay(backoff)` (NATS 2.10+):

| Delivery # | Backoff | Cumulative Wait |
|-----------|---------|-----------------|
| 1 | 0s (first attempt) | 0s |
| 2 | 1s | 1s |
| 3 | 2s | 3s |
| 4 | 4s | 7s |
| 5 | 8s | 15s |
| 6+ | capped at 30s | varies |

This prevents premature DLQ routing during transient failures (e.g., database restart). Compliant with ERROR_HANDLING_GUIDELINE Section 4.1.

### 9.3 Critical Design Decision: DLQ Failure

When all DLQ publish attempts fail, `msg.ack()` is still called. Rationale:
- Not acking creates infinite re-delivery loop
- Infinite loop blocks the entire consumer
- Message loss is less catastrophic than system-wide consumer blockage
- The `log.error` + metrics ensures the failure is visible and alertable
- DLQ has two-tier reliability: JetStream (persistent) → Core NATS (best-effort) → drop

---

## 10. Testing Strategy

### 10.1 New Unit Tests (15 cases)

| # | Test Class | Cases | Tools |
|---|-----------|-------|-------|
| 1 | `JetStreamInboundEventChannelAdapterTest` | 10 | JUnit 5 + Mockito |
| 2 | `JetStreamOutboundEventChannelAdapterTest` | 3 | JUnit 5 + Mockito |
| 3 | `JetStreamStreamManagerTest` | 3 | JUnit 5 + Mockito |
| 4 | `NatsChannelMetricsTest` | 2 | SimpleMeterRegistry |
| 5 | `NatsChannelDefinitionProcessorTest` (additions) | +2 | JUnit 5 + Mockito |

**JetStreamInboundEventChannelAdapterTest (10 cases):**
- `handleMessage_success_acksMessage`
- `handleMessage_error_naksWithDelay`
- `handleMessage_error_backoffExponential`
- `handleMessage_error_metadataFails_fallsBackToPlainNak`
- `handleMessage_maxDeliverExceeded_publishesToDlq`
- `handleMessage_dlqJetStreamFails_fallbackToCoreNats`
- `handleMessage_dlqBothFail_stillAcks`
- `handleMessage_emptyBody_acksAndSkips`
- `handleMessage_dlqDisabled_acksWithoutPublish`
- `handleMessage_propagatesTraceIdToMdc`

**JetStreamOutboundEventChannelAdapterTest (3 cases):**
- `sendEvent_publishesToJetStream`
- `sendEvent_propagatesHeaders`
- `sendEvent_streamNotFound_throwsFlowableException`

**JetStreamStreamManagerTest (3 cases):**
- `ensureStream_exists_noAction`
- `ensureStream_notFound_creates`
- `ensureStream_apiFails_throwsFlowableException`

**NatsChannelMetricsTest (2 cases):**
- `counters_registeredCorrectly`
- `counters_incrementCorrectly`

**NatsChannelDefinitionProcessorTest additions (2 cases):**
- `registerInbound_jetstreamTrue_createsJetStreamAdapter`
- `registerOutbound_jetstreamTrue_createsJetStreamAdapter`

### 10.2 New Integration Tests (5 cases)

| # | Test Class | Cases | Tools |
|---|-----------|-------|-------|
| 6 | `JetStreamInboundIntegrationTest` | 3 | Testcontainers (nats:2.10-alpine --jetstream) |
| 7 | `JetStreamOutboundIntegrationTest` | 1 | Testcontainers |
| 8 | `JetStreamStreamManagerIntegrationTest` | 1 | Testcontainers |

**JetStreamInboundIntegrationTest (3 cases):**
- `inbound_receivesAndAcks` — publish to stream → adapter consumes → ack → no re-delivery
- `inbound_processingError_redelivers` — exception → nak → JetStream re-delivers
- `inbound_maxDeliverExceeded_sentToDlq` — 5x fail → message appears on DLQ subject

**JetStreamOutboundIntegrationTest (1 case):**
- `outbound_publishesToStream` — adapter publish → message in JetStream stream

**JetStreamStreamManagerIntegrationTest (1 case):**
- `autoCreateStream_createsAndVerifies` — stream doesn't exist → create → getStreamInfo succeeds

### 10.3 Testcontainers JetStream

```java
@Container
static GenericContainer<?> natsContainer = new GenericContainer<>("nats:2.10-alpine")
        .withCommand("--jetstream")
        .withExposedPorts(4222);
```

### 10.4 Test Summary

| Category | Phase 1 | Phase 2 New | Total |
|----------|---------|-------------|-------|
| Unit | 18 | 20 | 38 |
| Integration | 3 | 5 | 8 |
| **Total** | **21** | **25** | **46** |

---

## 11. Java 21 Requirement

### 11.1 pom.xml Change

```xml
<java.version>21</java.version>
```

### 11.2 Virtual Thread Impact

| Component | Impact |
|-----------|--------|
| JetStream outbound `publish()` | Sync call — safe on virtual thread, carrier thread released during I/O |
| JetStream inbound callback | jnats internal thread — no change needed |
| DLQ publish | Core NATS `publish()` — fire-and-forget, non-blocking |
| Stream auto-create | Startup-time only — no runtime impact |

---

## Appendix A: Key Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| 1 | Push-based consumer (not pull) | Consistent with Phase 1 pattern, Kafka adapter pattern. Pull adds complexity without clear benefit for Phase 2. |
| 2 | Ack-after-process + maxDeliver | Telco requirement: no message loss. maxDeliver prevents poison message infinite loops. |
| 3 | Auto-create stream with guard | Dev/test convenience, production safety (default off). |
| 4 | Adapter-managed DLQ (not server advisory) | Failed messages go to separate subject for independent processing. Enables DLQ-triggered recovery workflows. |
| 5 | Sync publish on virtual thread | No blocking of OS resources. Flowable process knows if publish succeeded. Go goroutine equivalent. |
| 6 | DLQ via JetStream + Core NATS fallback | Primary: JetStream publish for guaranteed persistence. Fallback: Core NATS best-effort. Virtual thread makes sync JetStream DLQ publish safe. |
| 7 | DLQ failure → still ack | Infinite re-delivery loop is worse than message loss. Two-tier DLQ reliability minimizes actual loss. |
| 8 | Structured logging retrofit | OBSERVABILITY_GUIDELINE compliance. kv() format enables Loki/Grafana structured queries. |
| 9 | MDC trace propagation | OBSERVABILITY_GUIDELINE [BLOCKING]: trace_id mandatory in all logs. |
| 10 | NKey auth | Telco security standard — user/pass insufficient for production NATS deployments. |
| 11 | Inbound virtual thread offloading | NATS dispatcher threads must not be blocked by Flowable DB/logic. VirtualThreadPerTaskExecutor mirrors Go's `go func()` pattern. |
| 12 | nakWithDelay exponential backoff | Prevents premature DLQ routing during transient failures. ERROR_HANDLING_GUIDELINE Section 4.1 compliant. |
| 13 | Outbound header propagation | headerMap → NATS Headers for end-to-end traceability (X-Trace-Id, X-Correlation-Id). |
| 14 | Processing Timer metric | P95/P99 duration monitoring for eventReceived() — detects bottlenecks before slow consumer. |

## Appendix B: Guidelines Compliance

| Guideline | Requirement | Status |
|-----------|-------------|--------|
| OBSERVABILITY_GUIDELINE | Structured JSON logging | ✅ kv() format (new + Phase 1 retrofit) |
| OBSERVABILITY_GUIDELINE | trace_id in all logs | ✅ MDC propagation from X-Trace-Id header |
| OBSERVABILITY_GUIDELINE | Metric naming convention | ✅ Micrometer dot → Prometheus snake_case |
| OBSERVABILITY_GUIDELINE | Max 5 labels per metric | ✅ 2 labels (subject, channel) |
| OBSERVABILITY_GUIDELINE | Duration metrics (histogram) | ✅ Processing Timer with Prometheus buckets |
| ERROR_HANDLING_GUIDELINE | Business violations = WARN | ✅ DLQ routing = WARN, processing error = ERROR |
| ERROR_HANDLING_GUIDELINE | Retry with exponential backoff | ✅ nakWithDelay() with 2^n backoff, 30s cap |
| ERROR_HANDLING_GUIDELINE | DLQ for unprocessable messages | ✅ JetStream DLQ (persistent) + Core NATS fallback |
| ERROR_HANDLING_GUIDELINE | Never silently drop failed messages | ✅ Two-tier DLQ + metrics + logging |
| EVENT_DRIVEN_ARCHITECTURE | Consumer idempotency | ✅ At-least-once, Flowable handles dedup |
| EVENT_DRIVEN_ARCHITECTURE | DLQ mandatory | ✅ Configurable DLQ subject |
| EVENT_DRIVEN_ARCHITECTURE | Monitor consumer lag | ✅ nak/dlq counters + processing timer for alerting |
| EVENT_DRIVEN_ARCHITECTURE | Header propagation | ✅ headerMap → NATS Headers (outbound), X-Trace-Id → MDC (inbound) |
| CODING_GUIDELINES_JAVA | Package organization | ✅ jetstream/, metrics/, config/ |
| CODING_GUIDELINES_JAVA | Structured logging with SLF4J | ✅ StructuredArguments.kv() |

## Appendix C: Phase Roadmap Reference

| Phase | Scope | Status |
|-------|-------|--------|
| 1 | Core NATS pub/sub adapter | ✅ Complete |
| 2 (this spec) | JetStream + Metrics + Observability | In progress |
| 3 | Request-Reply pattern | Planned |
| 4 | Advanced features (key-value, object store) | Planned |
