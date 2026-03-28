# Phase 1: Flowable NATS Channel — Core Adapter Design Spec

## Overview

Flowable Event Registry NATS channel adapter. Enables Flowable processes to send/receive events via NATS messaging. Follows the Lean Adapter approach (Approach B): mirrors Kafka adapter's Flowable contract compliance while stripping NATS-irrelevant complexity (partitions, message keys, retry topics).

**Scope:** Core NATS pub/sub only. No JetStream (Phase 2), no Request-Reply (Phase 3).

**License:** Apache 2.0

---

## 1. Class Structure

7 production classes + 6 test classes.

```
src/main/java/org/flowable/eventregistry/spring/nats/
├── channel/
│   ├── NatsInboundChannelModel.java
│   └── NatsOutboundChannelModel.java
├── NatsChannelDefinitionProcessor.java
├── NatsInboundEventChannelAdapter.java
├── NatsOutboundEventChannelAdapter.java
├── NatsInboundEvent.java
└── config/
    ├── NatsChannelAutoConfiguration.java
    └── NatsProperties.java

src/test/java/org/flowable/eventregistry/spring/nats/
├── NatsChannelDefinitionProcessorTest.java
├── NatsInboundEventChannelAdapterTest.java
├── NatsOutboundEventChannelAdapterTest.java
├── NatsInboundChannelIntegrationTest.java
├── NatsOutboundChannelIntegrationTest.java
└── config/
    └── NatsChannelAutoConfigurationTest.java
```

### Flowable Interface Mapping

| Flowable Interface | Our Class |
|--------------------|-----------|
| `ChannelModelProcessor` | `NatsChannelDefinitionProcessor` |
| `InboundEventChannelAdapter` | `NatsInboundEventChannelAdapter` |
| `OutboundEventChannelAdapter<String>` | `NatsOutboundEventChannelAdapter` |
| `InboundEvent` | `NatsInboundEvent` |
| `InboundChannelModel` (extend) | `NatsInboundChannelModel` |
| `OutboundChannelModel` (extend) | `NatsOutboundChannelModel` |

---

## 2. Data Flow

### 2.1 Inbound (NATS -> Flowable)

```
NATS Server ──► NatsInboundEventChannelAdapter ──► Flowable Event Registry Engine
  subject         1. Dispatcher callback              ● deserialize
  queueGroup      2. Wrap -> NatsInboundEvent          ● correlate
                  3. triggerEventInstance(event)        ● trigger process
```

**Steps:**

1. `NatsChannelDefinitionProcessor` parses channel JSON -> creates `NatsInboundChannelModel`
2. Processor creates `NatsInboundEventChannelAdapter`, sets on model
3. Adapter calls `connection.subscribe(subject, queueGroup, dispatcher)` -> NATS subscription starts
4. NATS message arrives -> dispatcher callback fires
5. `Message` wrapped into `NatsInboundEvent` (body as UTF-8 String + headers as Map)
6. `triggerEventInstance(event)` -> handed to Flowable

### 2.2 Outbound (Flowable -> NATS)

```
Flowable Engine ──► NatsOutboundEventChannelAdapter ──► NATS Server
  process sends       1. sendEvent(body)                   subject
  event               2. connection.publish(subj, bytes)
```

**Steps:**

1. `NatsChannelDefinitionProcessor` parses channel JSON -> creates `NatsOutboundChannelModel`
2. Processor creates `NatsOutboundEventChannelAdapter`, sets on model
3. Flowable process sends event -> `adapter.sendEvent(serializedBody)` called
4. Adapter calls `connection.publish(subject, body.getBytes(UTF_8))`

### 2.3 Lifecycle

- **Startup:** AutoConfiguration creates Connection bean -> Processor bean -> Flowable deploys channel definitions -> inbound channels subscribe, outbound adapters ready
- **Shutdown:** `InboundAdapter.unsubscribe()` -> `subscription.drain()` + unsubscribe -> `Connection.close()` (Spring lifecycle)
- **Channel undeploy:** `InboundAdapter.unsubscribe()` called independently of application shutdown

### 2.4 Thread Model

| Component | Thread | Notes |
|-----------|--------|-------|
| NATS subscription callback | jnats internal thread pool | Library-managed |
| `triggerEventInstance()` | Same callback thread | Flowable takes synchronously, queues internally |
| Outbound `publish()` | Caller (Flowable engine) thread | Non-blocking, writes to TCP buffer |

---

## 3. Spring Boot Auto-Configuration

### 3.1 NatsProperties

```java
@ConfigurationProperties(prefix = "spring.nats")
```

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `url` | String | `nats://localhost:4222` | NATS server URL |
| `username` | String | null | Username auth |
| `password` | String | null | Password auth |
| `token` | String | null | Token auth |
| `credentials-file` | String | null | Credentials file path |
| `connection-timeout` | Duration | 5s | Connection timeout |
| `max-reconnects` | int | -1 (infinite) | Max reconnection attempts |
| `reconnect-wait` | Duration | 2s | Wait between reconnects |
| `tls.enabled` | boolean | false | Enable TLS |
| `tls.cert-file` | String | null | Client certificate |
| `tls.key-file` | String | null | Client private key |
| `tls.ca-file` | String | null | CA certificate |

Auth priority: credentialsFile > token > username/password.

### 3.2 NatsChannelAutoConfiguration

```java
@AutoConfiguration
@ConditionalOnClass({ Connection.class, EventRegistryEngine.class })
@EnableConfigurationProperties(NatsProperties.class)
```

**Beans:**

| Bean | Condition | destroyMethod |
|------|-----------|---------------|
| `Connection natsConnection(NatsProperties)` | `@ConditionalOnMissingBean` | `close` |
| `NatsChannelDefinitionProcessor(Connection)` | `@ConditionalOnMissingBean` | — |

**Connection features:**
- ConnectionListener: logs CONNECTED, RECONNECTED, DISCONNECTED, CLOSED
- ErrorListener: logs errors, exceptions, slow consumer detection

**Registration:** `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (Spring Boot 3.x style)

### 3.3 Minimal Configuration Example

```yaml
spring:
  nats:
    url: nats://nats.infra:4222
    username: flowable-svc
    password: ${NATS_PASSWORD}
    max-reconnects: -1
    reconnect-wait: 2s
    tls:
      enabled: true
      ca-file: /etc/ssl/nats-ca.pem
```

---

## 4. Channel Definition JSON Contracts

### 4.1 Inbound Channel

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
    { "name": "queueGroup", "stringValue": "order-service" },
    { "name": "jetstream", "stringValue": "false" }
  ]
}
```

| Field | Required | Description |
|-------|----------|-------------|
| `type` | Yes | Must be `"nats"` |
| `subject` | Yes | NATS subscribe subject (dot-separated) |
| `queueGroup` | No | Queue group for load-balanced consumption |
| `jetstream` | No | Default `"false"`. Reserved for Phase 2 |

### 4.2 Outbound Channel

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
    { "name": "jetstream", "stringValue": "false" }
  ]
}
```

| Field | Required | Description |
|-------|----------|-------------|
| `subject` | Yes | NATS publish subject |
| `jetstream` | No | Default `"false"`. Reserved for Phase 2 |

### 4.3 Processor Parsing

- `canProcess()` matches `type == "nats"`
- Extracts fields from `channelFields` list into `Map<String, String>`
- `requireField("subject")` -> throws `FlowableException` if missing/blank
- `jetstream == true` -> throws `FlowableException("JetStream is not yet supported")`
- Routes to `registerInbound()` or `registerOutbound()` based on `channelType`

---

## 5. Error Handling

### 5.1 Error Taxonomy

| Category | Error | Strategy |
|----------|-------|----------|
| **Infrastructure** | Initial connection failure | Fail-fast, Spring context fails to start |
| | Disconnection | jnats auto-reconnect, log WARN |
| | Reconnection successful | Subscriptions auto-restored, log WARN |
| | Max reconnects exceeded | Connection CLOSED, subscriptions stop |
| | Slow consumer | Log WARN (monitoring alert) |
| **Channel Registration** | Missing required field | `FlowableException` with clear message |
| | JetStream flag true | `FlowableException("not yet supported")` |
| | Subscribe failure | `FlowableException` wrapping cause |
| **Message Processing** | Empty message body | Log WARN + skip, subscription continues |
| | Processing exception | Log ERROR + continue, subscription continues |
| **Outbound** | Connection not available | `FlowableException` wrapping `IllegalStateException` |

### 5.2 Key Principles

1. **Fail-fast on startup:** If NATS connection cannot be established, application does not start
2. **Never kill subscriptions on message errors:** NATS Core has no ack/nack — message is already consumed. Exception propagation would only break the subscription with no benefit
3. **All errors wrapped as FlowableException:** Flowable engine handles its own error pipeline
4. **Connection events logged:** CONNECTED, RECONNECTED, DISCONNECTED, CLOSED — enables operational monitoring
5. **Slow consumer detection logged:** Critical for high-throughput telco workloads where NATS may drop messages

### 5.3 Connection Listeners

```java
// ConnectionListener
CONNECTED    -> log.info
RECONNECTED  -> log.warn
DISCONNECTED -> log.warn
CLOSED       -> log.info

// ErrorListener
errorOccurred()        -> log.error
exceptionOccurred()    -> log.error
slowConsumerDetected() -> log.warn
```

---

## 6. Testing Strategy

### 6.1 Test Suite

| # | Test Class | Type | Cases | Tools |
|---|-----------|------|-------|-------|
| 1 | `NatsChannelDefinitionProcessorTest` | Unit | 8 | JUnit 5 + Mockito |
| 2 | `NatsInboundEventChannelAdapterTest` | Unit | 4 | JUnit 5 + Mockito |
| 3 | `NatsOutboundEventChannelAdapterTest` | Unit | 2 | JUnit 5 + Mockito |
| 4 | `NatsInboundChannelIntegrationTest` | Integration | 2 | Testcontainers (nats:2.10-alpine) |
| 5 | `NatsOutboundChannelIntegrationTest` | Integration | 1 | Testcontainers (nats:2.10-alpine) |
| 6 | `NatsChannelAutoConfigurationTest` | Unit | 3 | ApplicationContextRunner |
| | **Total** | | **20** | |

### 6.2 Unit Test Details

**NatsChannelDefinitionProcessorTest (8 cases):**
- `canProcess_natsType_returnsTrue`
- `canProcess_otherType_returnsFalse`
- `registerInbound_validFields_subscribes`
- `registerInbound_noQueueGroup_subscribesWithout`
- `registerOutbound_validFields_createsAdapter`
- `registerInbound_missingSubject_throwsException`
- `registerInbound_jetstreamTrue_throwsException`
- `unregisterChannelModel_unsubscribes`

**NatsInboundEventChannelAdapterTest (4 cases):**
- `handleMessage_validMessage_triggersEvent`
- `handleMessage_emptyBody_skips`
- `handleMessage_processingError_continuesSubscription`
- `unsubscribe_drainsAndUnsubscribes`

**NatsOutboundEventChannelAdapterTest (2 cases):**
- `sendEvent_publishesMessage`
- `sendEvent_connectionClosed_throwsFlowableException`

**NatsChannelAutoConfigurationTest (3 cases):**
- `autoConfig_withAllDependencies_createsBeans`
- `autoConfig_customConnectionBean_usesCustom`
- `autoConfig_missingFlowable_doesNotLoad`

### 6.3 Integration Test Details

**NatsInboundChannelIntegrationTest (2 cases):**
- `inboundChannel_receivesAndProcessesEvent` — publish to NATS, assert Flowable event triggered
- `inboundChannel_withQueueGroup_loadBalances` — 2 subscriptions, 1 message, assert only 1 receives

**NatsOutboundChannelIntegrationTest (1 case):**
- `outboundChannel_publishesEvent` — trigger Flowable event, assert NATS message received

### 6.4 Test Dependencies

- `spring-boot-starter-test` (scope: test)
- `org.testcontainers:testcontainers` (scope: test)
- `org.testcontainers:junit-jupiter` (scope: test)

### 6.5 Performance Target

Full test suite < 10 seconds (NATS container starts in ~50ms).

---

## Appendix A: Key Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| 1 | Core NATS only (no JetStream) | Phase 1 scope — keep adapter simple, JetStream in Phase 2 |
| 2 | `jetstream` field reserved in JSON | Forward-compatible — Phase 2 activates existing branch point |
| 3 | Single Connection bean via auto-config | No nats-spring-boot-starter dependency — full control over telco-grade connection params |
| 4 | Lean Adapter approach | Kafka pattern compliance minus NATS-irrelevant concepts (partitions, offsets, retry topics) |
| 5 | Testcontainers over embedded/mock | Real NATS server — fast startup (~50ms), higher confidence than mocks |
| 6 | `subscription.drain()` on shutdown | Process in-flight messages before closing — telco-proven pattern |
| 7 | Never kill subscription on message error | NATS Core at-most-once — exception propagation would break subscription with no retry benefit |

## Appendix B: Telco NATS Patterns (Reference)

Source: 8 production NATS projects (cn-advanced-ota, ss7-3eai-gateway, ss7-ha-gateway, sbi-ha-gateway, ip-sm-gateway, diameter-ha-gateway, converged-eir, cloud-native-smsc).

- **Connection:** infinite reconnect, 1-2s wait, 2-5s timeout
- **Subjects:** dot-separated hierarchy (e.g., `map.mt.sms.request`, `eir.v1.event.device.seen`)
- **Queue groups:** service-name based (e.g., `sbi-gateway`, `ss7-gateway-group`)
- **Headers:** X-Trace-Id, X-Correlation-Id, Content-Type
- **Auth:** user/pass, token, credentials file, optional TLS/mTLS

## Appendix C: Phase Roadmap Reference

| Phase | Scope |
|-------|-------|
| 1 (this spec) | Core NATS pub/sub adapter |
| 2 | JetStream support (durable consumers, exactly-once) |
| 3 | Request-Reply pattern |
| 4 | Advanced features (key-value, object store) |
