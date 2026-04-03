# Phase 5: Camunda 7 Compatibility — Multi-Module Refactoring + Camunda NATS Adapter

## Overview

Refactor the project from a single module to a multi-module Maven project, extract shared NATS infrastructure into `nats-core`, and build a new `camunda-nats-channel` module that provides NATS messaging for Camunda 7 BPM Platform (7.24).

**Key Finding:** Camunda 7 has no Event Registry. Inbound events use `runtimeService.correlateMessage()`, outbound uses JavaDelegate. The Flowable Event Registry adapter pattern (ChannelModelProcessor, InboundEventChannelAdapter) cannot be ported — different extension points are required.

**Scope:**
- Multi-module refactoring (parent pom, nats-core, flowable-nats-channel, camunda-nats-channel)
- Camunda 7 inbound: NATS/JetStream → Message Correlation (Core NATS + JetStream with ack/nack/DLQ)
- Camunda 7 outbound: NatsPublishDelegate, JetStreamPublishDelegate, NatsRequestReplyDelegate
- YAML-based subscription configuration (Camunda has no channel JSON)
- Auto-configuration with Camunda ProcessEngine detection

**Prerequisites:**
- Phase 1-4 complete (14 production classes, 55 tests, CI/CD ready)
- Java 21+
- Camunda 7.24.0 (latest community edition, archived)

**License:** Apache 2.0

---

## 1. Multi-Module Project Structure

### 1.1 Module Layout

```
flowable-nats-channel/                          ← parent pom (aggregator)
├── pom.xml                                     ← parent: modules, shared deps, release profile
├── nats-core/                                  ← shared module
│   ├── pom.xml
│   └── src/main/java/com/threeai/nats/core/
│       ├── NatsConnectionFactory.java          ← Connection builder logic
│       ├── NatsHeaderUtils.java                ← header Map → NATS Headers conversion
│       ├── NatsProperties.java                 ← spring.nats.* configuration binding
│       ├── metrics/
│       │   └── NatsChannelMetrics.java         ← Micrometer counters + Timer
│       └── jetstream/
│           └── JetStreamStreamManager.java     ← auto-create stream guard
│
├── flowable-nats-channel/                      ← Flowable module (refactored from current)
│   ├── pom.xml                                 ← depends on nats-core
│   └── src/main/java/org/flowable/eventregistry/spring/nats/
│       ├── (all existing Flowable-specific classes)
│       └── config/
│           └── FlowableNatsAutoConfiguration.java
│
├── camunda-nats-channel/                       ← NEW: Camunda 7 module
│   ├── pom.xml                                 ← depends on nats-core + camunda-bpm
│   └── src/main/java/com/threeai/nats/camunda/
│       ├── inbound/
│       │   ├── NatsMessageCorrelationSubscriber.java
│       │   ├── JetStreamMessageCorrelationSubscriber.java
│       │   ├── NatsSubscriptionRegistrar.java
│       │   └── SubscriptionConfig.java
│       ├── outbound/
│       │   ├── NatsPublishDelegate.java
│       │   ├── JetStreamPublishDelegate.java
│       │   └── NatsRequestReplyDelegate.java
│       └── config/
│           └── CamundaNatsAutoConfiguration.java
│
├── README.md
├── RELEASING.md
└── .github/workflows/
```

### 1.2 What Moves to nats-core

| Class | Current Location | New Location |
|-------|-----------------|-------------|
| `NatsProperties` | `o.f.e.s.nats.config` | `com.threeai.nats.core` |
| `NatsHeaderUtils` | `o.f.e.s.nats` | `com.threeai.nats.core` |
| `NatsChannelMetrics` | `o.f.e.s.nats.metrics` | `com.threeai.nats.core.metrics` |
| `JetStreamStreamManager` | `o.f.e.s.nats.jetstream` | `com.threeai.nats.core.jetstream` |
| Connection creation logic | `NatsChannelAutoConfiguration` | `NatsConnectionFactory` |

### 1.3 What Stays in flowable-nats-channel

All Flowable-specific classes remain with unchanged package names (`org.flowable.eventregistry.spring.nats.*`). Imports updated to reference `nats-core` classes:

- `NatsChannelDefinitionProcessor`
- `NatsInboundEventChannelAdapter` / `NatsOutboundEventChannelAdapter`
- `NatsInboundEvent`
- `NatsInboundChannelModel` / `NatsOutboundChannelModel`
- `JetStreamInboundEventChannelAdapter` / `JetStreamOutboundEventChannelAdapter`
- `NatsRequestReplyDelegate` (Flowable version)
- `FlowableNatsAutoConfiguration` (renamed from `NatsChannelAutoConfiguration`)

### 1.4 Package Naming

| Module | Maven Artifact | Package |
|--------|---------------|---------|
| nats-core | `com.3eai:nats-core` | `com.threeai.nats.core` |
| flowable-nats-channel | `com.3eai:flowable-nats-channel` | `org.flowable.eventregistry.spring.nats` |
| camunda-nats-channel | `com.3eai:camunda-nats-channel` | `com.threeai.nats.camunda` |

### 1.5 Parent POM Structure

```xml
<groupId>com.3eai</groupId>
<artifactId>nats-channel-parent</artifactId>
<version>0.1.0-SNAPSHOT</version>
<packaging>pom</packaging>

<modules>
    <module>nats-core</module>
    <module>flowable-nats-channel</module>
    <module>camunda-nats-channel</module>
</modules>
```

Shared dependency versions, release profile, and CI configuration in parent. Each child module declares only its specific dependencies.

---

## 2. Camunda 7 Inbound — Message Correlation

### 2.1 How It Works

Camunda 7 has no Event Registry. Inbound events are delivered via `runtimeService.correlateMessage()`:

```
NATS Server          Subscriber                         Camunda 7 Engine
    │                    │                                    │
    │── message ────────▶│                                    │
    │                    │── correlateMessage(messageName) ──▶│
    │                    │   .processInstanceBusinessKey()    │── trigger catch event
    │                    │   .setVariables(payload)           │   or start new process
    │                    │◀── CorrelationResult ──────────────│
    │                    │── msg.ack() (JetStream) ──────────│
```

### 2.2 Subscription Configuration (YAML)

Camunda has no channel JSON. Subscriptions are defined in `application.yml`:

```yaml
spring:
  nats:
    url: nats://localhost:4222
    camunda:
      subscriptions:
        - subject: event.order.created
          message-name: OrderCreated
          business-key-header: X-Business-Key
          jetstream: false

        - subject: event.payment.received
          message-name: PaymentReceived
          business-key-variable: orderId
          jetstream: true
          durable-name: payment-consumer
          max-deliver: 5
          dlq-subject: dlq.event.payment.received
          auto-create-stream: false
          stream-name: PAYMENTS
```

### 2.3 SubscriptionConfig

```java
package com.threeai.nats.camunda.inbound;

public class SubscriptionConfig {
    private String subject;                    // NATS subject
    private String messageName;                // Camunda BPMN message name
    private String businessKeyHeader;          // extract business key from header
    private String businessKeyVariable;        // extract business key from payload field
    private boolean jetstream;                 // JetStream mode
    private String durableName;                // JetStream durable consumer
    private int maxDeliver = 5;                // max retries before DLQ
    private String dlqSubject;                 // DLQ subject
    private boolean autoCreateStream;          // auto-create JetStream stream
    private String streamName;                 // target stream name
    // getters + setters
}
```

### 2.4 NatsMessageCorrelationSubscriber (Core NATS)

```java
public class NatsMessageCorrelationSubscriber {

    private final Connection connection;
    private final RuntimeService runtimeService;
    private final SubscriptionConfig config;
    private final NatsChannelMetrics metrics;  // nullable
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private Dispatcher dispatcher;

    public void subscribe() {
        this.dispatcher = connection.createDispatcher();
        dispatcher.subscribe(config.getSubject(), msg -> {
            executor.submit(() -> handleMessage(msg));
        });
    }

    void handleMessage(Message msg) {
        String traceId = NatsHeaderUtils.extractHeader(msg, "X-Trace-Id");
        try {
            if (traceId != null) MDC.put("trace_id", traceId);

            if (msg.getData() == null || msg.getData().length == 0) {
                log.warn("Empty message received, skipping",
                        kv("subject", config.getSubject()));
                return;
            }

            Map<String, Object> variables = parseJsonPayload(msg);
            String businessKey = resolveBusinessKey(msg, variables, config);

            MessageCorrelationBuilder builder = runtimeService
                    .createMessageCorrelation(config.getMessageName())
                    .setVariables(variables);

            if (businessKey != null) {
                builder.processInstanceBusinessKey(businessKey);
            }

            builder.correlateWithResult();

            if (metrics != null) metrics.consumeCount(config.getSubject(), "camunda").increment();
        } catch (Exception e) {
            log.error("Message correlation failed",
                    kv("subject", config.getSubject()),
                    kv("message_name", config.getMessageName()), e);
        } finally {
            MDC.remove("trace_id");
        }
    }

    public void unsubscribe() {
        if (dispatcher != null) {
            dispatcher.drain(Duration.ofSeconds(5));
            connection.closeDispatcher(dispatcher);
        }
        executor.close();
    }
}
```

### 2.5 JetStreamMessageCorrelationSubscriber

Same pattern as Section 2.4 but with JetStream semantics:

- Push-based JetStream consumer with virtual thread offloading
- `correlateWithResult()` success → `msg.ack()`
- `correlateWithResult()` failure → `msg.nakWithDelay(calculateBackoff(deliveryCount))`
- `numDelivered() > maxDeliver` → DLQ publish (JetStream primary + Core NATS fallback) → `msg.ack()`
- Exponential backoff: 1s, 2s, 4s, 8s, 16s, 30s cap
- Server-side `maxDeliver = -1` (adapter manages DLQ routing)

All patterns identical to Flowable's `JetStreamInboundEventChannelAdapter` — only the event delivery mechanism differs (`correlateWithResult()` instead of `eventRegistry.eventReceived()`).

### 2.6 NatsSubscriptionRegistrar

Spring bean that reads `spring.nats.camunda.subscriptions` from configuration, creates appropriate subscriber instances (Core NATS or JetStream), and manages their lifecycle:

```java
@Component
public class NatsSubscriptionRegistrar implements DisposableBean {

    private final List<NatsMessageCorrelationSubscriber> coreSubscribers = new ArrayList<>();
    private final List<JetStreamMessageCorrelationSubscriber> jetStreamSubscribers = new ArrayList<>();

    @PostConstruct
    public void init() {
        for (SubscriptionConfig config : properties.getCamunda().getSubscriptions()) {
            if (config.isJetstream()) {
                // create + subscribe JetStream subscriber
            } else {
                // create + subscribe Core NATS subscriber
            }
        }
    }

    @Override
    public void destroy() {
        // unsubscribe all
    }
}
```

### 2.7 Business Key Resolution

Two strategies configured per subscription:

| Config Field | Strategy | Example |
|-------------|----------|---------|
| `business-key-header` | Extract from NATS message header | `X-Business-Key: ORDER-123` |
| `business-key-variable` | Extract from JSON payload field | `{"orderId": "ORDER-123"}` → field `orderId` |

If neither is configured, no business key constraint is used (correlates to any waiting process instance with matching message name).

---

## 3. Camunda 7 Outbound — Delegates

### 3.1 NatsPublishDelegate

JavaDelegate that publishes to NATS from a Camunda service task:

```java
package com.threeai.nats.camunda.outbound;

public class NatsPublishDelegate implements org.camunda.bpm.engine.delegate.JavaDelegate {

    private final Connection connection;
    private final NatsChannelMetrics metrics;

    private org.camunda.bpm.engine.delegate.Expression subject;
    private org.camunda.bpm.engine.delegate.Expression payloadVariable;

    @Override
    public void execute(org.camunda.bpm.engine.delegate.DelegateExecution execution) throws Exception {
        String subjectVal = getRequiredString(subject, execution, "subject");
        byte[] data = serializePayload(execution.getVariable(payloadVar));

        NatsMessage message = NatsMessage.builder()
                .subject(subjectVal)
                .data(data)
                .headers(NatsHeaderUtils.toNatsHeaders(extractProcessHeaders(execution)))
                .build();
        connection.publish(message);

        if (metrics != null) metrics.publishCount(subjectVal, "camunda").increment();
    }
}
```

BPMN: `<serviceTask camunda:delegateExpression="${natsPublish}">`

### 3.2 JetStreamPublishDelegate

Same as NatsPublishDelegate but uses `jetStream.publish(message)` for guaranteed persistence.

BPMN: `<serviceTask camunda:delegateExpression="${jetStreamPublish}">`

### 3.3 NatsRequestReplyDelegate

Nearly identical to Flowable's Phase 3 delegate with Camunda imports:

| Flowable | Camunda 7 |
|----------|-----------|
| `org.flowable.engine.delegate.JavaDelegate` | `org.camunda.bpm.engine.delegate.JavaDelegate` |
| `org.flowable.engine.delegate.DelegateExecution` | `org.camunda.bpm.engine.delegate.DelegateExecution` |
| `org.flowable.common.engine.api.delegate.Expression` | `org.camunda.bpm.engine.delegate.Expression` |
| `FlowableException` | `ProcessEngineException` |

Same logic: `connection.request(subject, data, timeout)` → reply → `execution.setVariable(resultVar, replyBody)`. Virtual thread safe. Exponential timeout handling.

BPMN: `<serviceTask camunda:delegateExpression="${natsRequestReply}">`

---

## 4. Auto-Configuration

### 4.1 CamundaNatsAutoConfiguration

```java
@AutoConfiguration
@ConditionalOnClass({ org.camunda.bpm.engine.ProcessEngine.class, Connection.class })
@EnableConfigurationProperties(NatsProperties.class)
public class CamundaNatsAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public Connection natsConnection(NatsProperties props) throws Exception {
        return NatsConnectionFactory.create(props);
    }

    @Bean @ConditionalOnMissingBean
    public JetStream natsJetStream(Connection connection) throws IOException {
        return connection.jetStream();
    }

    @Bean @ConditionalOnMissingBean
    public JetStreamStreamManager jetStreamStreamManager() {
        return new JetStreamStreamManager();
    }

    @Bean @ConditionalOnMissingBean @ConditionalOnBean(MeterRegistry.class)
    public NatsChannelMetrics natsChannelMetrics(MeterRegistry registry) {
        return new NatsChannelMetrics(registry);
    }

    // Inbound subscription registrar
    @Bean
    public NatsSubscriptionRegistrar natsSubscriptionRegistrar(
            Connection connection, JetStream jetStream, RuntimeService runtimeService,
            JetStreamStreamManager streamManager, NatsProperties props,
            @Autowired(required = false) NatsChannelMetrics metrics) {
        return new NatsSubscriptionRegistrar(
                connection, jetStream, runtimeService, streamManager, props, metrics);
    }

    // Outbound delegates (prototype-scoped for thread safety)
    @Bean @Scope("prototype")
    public NatsPublishDelegate natsPublish(Connection connection,
            @Autowired(required = false) NatsChannelMetrics metrics) {
        return new NatsPublishDelegate(connection, metrics);
    }

    @Bean @Scope("prototype")
    public JetStreamPublishDelegate jetStreamPublish(JetStream jetStream,
            @Autowired(required = false) NatsChannelMetrics metrics) {
        return new JetStreamPublishDelegate(jetStream, metrics);
    }

    @Bean @Scope("prototype")
    public NatsRequestReplyDelegate natsRequestReply(Connection connection,
            @Autowired(required = false) NatsChannelMetrics metrics) {
        return new NatsRequestReplyDelegate(connection, metrics);
    }
}
```

### 4.2 NatsProperties Extension (nats-core)

Add Camunda subscription config to existing `NatsProperties`:

```java
public class NatsProperties {
    // ... existing fields (url, auth, tls, etc.) ...

    private final Camunda camunda = new Camunda();

    public static class Camunda {
        private List<SubscriptionConfig> subscriptions = new ArrayList<>();
        // getter + setter
    }
}
```

### 4.3 NatsConnectionFactory (extracted from AutoConfiguration)

```java
package com.threeai.nats.core;

public final class NatsConnectionFactory {

    public static Connection create(NatsProperties props) throws IOException, InterruptedException {
        Options.Builder builder = new Options.Builder()
                .server(props.getUrl())
                .connectionTimeout(props.getConnectionTimeout())
                .maxReconnects(props.getMaxReconnects())
                .reconnectWait(props.getReconnectWait())
                // ... ConnectionListener, ErrorListener (structured logging) ...
                ;
        configureAuth(builder, props);
        return Nats.connect(builder.build());
    }
}
```

Both `FlowableNatsAutoConfiguration` and `CamundaNatsAutoConfiguration` call `NatsConnectionFactory.create(props)`.

---

## 5. Testing Strategy

### 5.1 Test Distribution

| # | Test Class | Module | Type | Cases |
|---|-----------|--------|------|-------|
| **nats-core** | | | | |
| 1 | `NatsConnectionFactoryTest` | nats-core | Unit | 2 |
| 2 | `NatsChannelMetricsTest` | nats-core | Unit | 3 |
| 3 | `JetStreamStreamManagerTest` | nats-core | Unit | 3 |
| **camunda-nats-channel** | | | | |
| 4 | `NatsMessageCorrelationSubscriberTest` | camunda | Unit | 4 |
| 5 | `JetStreamMessageCorrelationSubscriberTest` | camunda | Unit | 6 |
| 6 | `NatsPublishDelegateTest` | camunda | Unit | 3 |
| 7 | `JetStreamPublishDelegateTest` | camunda | Unit | 2 |
| 8 | `NatsRequestReplyDelegateTest` | camunda | Unit | 4 |
| 9 | `CamundaNatsAutoConfigurationTest` | camunda | Unit | 3 |
| 10 | `CamundaInboundIntegrationTest` | camunda | Integration | 3 |
| 11 | `CamundaOutboundIntegrationTest` | camunda | Integration | 2 |
| **flowable-nats-channel** | | | | |
| 12-20 | (existing tests, relocated) | flowable | Unit+Integration | 47 |

### 5.2 Test Summary

| Module | Unit | Integration | Total |
|--------|------|-------------|-------|
| nats-core | 8 | 0 | 8 |
| flowable-nats-channel | 37 | 10 | 47 |
| camunda-nats-channel | 22 | 5 | 27 |
| **Total** | **67** | **15** | **82** |

### 5.3 Migration Test Invariant

All 55 existing tests must pass after every refactoring step. Tests are redistributed across modules but none are deleted.

### 5.4 Camunda Integration Tests

Use Testcontainers for NATS + mock `RuntimeService` (Camunda engine is too heavy for unit-style integration tests):

- `inbound_coreNats_correlatesMessage` — publish to NATS → subscriber calls `correlateWithResult()` on mock RuntimeService
- `inbound_jetStream_acksAfterCorrelation` — JetStream publish → subscriber correlates → ack → no re-delivery
- `inbound_jetStream_maxDeliverExceeded_dlq` — correlation always fails → DLQ
- `outbound_publishDelegate_sendsToNats` — delegate publishes → subscriber receives
- `outbound_requestReply_workerResponds` — delegate sends request → worker responds → result stored

---

## 6. Migration Sequence

The refactoring must be sequenced carefully to keep tests green:

| Step | Action | Tests After |
|------|--------|-------------|
| 1 | Create parent pom + module structure (empty modules) | 55 (unchanged) |
| 2 | Move shared classes to nats-core + update flowable imports | 55 (all green) |
| 3 | Rename FlowableNatsAutoConfiguration, update to use NatsConnectionFactory | 55 (all green) |
| 4 | Create camunda-nats-channel module with inbound subscribers | 55 + new camunda tests |
| 5 | Add camunda outbound delegates | + more tests |
| 6 | Add camunda auto-configuration | + config tests |
| 7 | Integration tests | Total: 82 |

---

## Appendix A: Key Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| 1 | Multi-module project (not separate repo) | Shared nats-core, single CI/CD, atomic releases |
| 2 | YAML-based subscriptions for Camunda | Camunda has no Event Registry / channel JSON |
| 3 | Message Correlation for inbound | Camunda's native mechanism for external event delivery |
| 4 | JavaDelegate for outbound (not Connect SPI) | Simpler, consistent with Flowable pattern, Camunda 7 is EOL |
| 5 | JetStream support in Camunda module | Telco workloads require persistence — same ack/nack/DLQ pattern |
| 6 | Mock RuntimeService in integration tests | Full Camunda engine too heavy — test NATS communication + mock engine |
| 7 | Prototype-scoped delegates | Thread safety — Expression field injection race condition |
| 8 | Virtual thread offloading for Camunda inbound | Same reasoning as Flowable — don't block NATS dispatcher |

## Appendix B: Camunda 7 API Quick Reference

| Concept | API |
|---------|-----|
| Message correlation | `runtimeService.createMessageCorrelation(name).correlateWithResult()` |
| JavaDelegate | `org.camunda.bpm.engine.delegate.JavaDelegate` |
| DelegateExecution | `org.camunda.bpm.engine.delegate.DelegateExecution` |
| Expression | `org.camunda.bpm.engine.delegate.Expression` |
| Engine exception | `org.camunda.bpm.engine.ProcessEngineException` |
| Process Engine Plugin | `org.camunda.bpm.engine.impl.cfg.ProcessEnginePlugin` |
| Spring Boot starter | `camunda-bpm-spring-boot-starter:7.24.0` |
| ConditionalOnClass | `org.camunda.bpm.engine.ProcessEngine` |

## Appendix C: Phase Roadmap

| Phase | Scope | Status |
|-------|-------|--------|
| 1 | Core NATS pub/sub adapter | ✅ Complete |
| 2 | JetStream + Metrics + Observability | ✅ Complete |
| 3 | Request-Reply service task | ✅ Complete |
| 4 | Documentation & Release | ✅ Complete |
| 5 (this spec) | Camunda 7 compatibility + multi-module | In progress |
| — | Load/stress testing | Before Maven Central |
| — | Maven Central publish | After testing |
