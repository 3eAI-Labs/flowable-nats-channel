# flowable-nats-channel

[![CI](https://github.com/3eAI-Labs/flowable-nats-channel/actions/workflows/ci.yml/badge.svg)](https://github.com/3eAI-Labs/flowable-nats-channel/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

NATS.io channel adapters for [Flowable](https://www.flowable.com/open-source) Event Registry and [Camunda 7](https://docs.camunda.org/manual/7.24/) BPM Platform. Enables BPMN/CMMN processes to send and receive events via NATS Core, JetStream, and Request-Reply.

## Why this project?

[Camunda 8](https://camunda.com/) (v8.6+, October 2024) moved all components — including Zeebe — to a paid enterprise license at **$50K+/year**. Camunda 7 has reached End of Life with no more security patches (October 2025).

[Flowable](https://www.flowable.com/open-source) is an open source (Apache 2.0) BPMN/CMMN/DMN engine with full BPMN 2.0 compatibility. This project adds [NATS.io](https://nats.io) messaging to Flowable's Event Registry, providing a **high-performance, zero-cost alternative** to the Camunda 8 + Zeebe stack.

## Who is this for?

- **Camunda 7 users facing EOL** — Migrate to Flowable (same BPMN 2.0 standard) with NATS messaging instead of paying $50K+/year for Camunda 8.
- **Camunda 7 fork maintainers** — Some organizations maintain forks of Camunda 7.x with their own security patches. This project offers a modern messaging layer for your architecture, or a full migration path to Flowable when ready.
- **Flowable users wanting NATS** — Add high-performance NATS messaging to your existing Flowable setup. Core NATS, JetStream, and Request-Reply out of the box.

## Comparison

| Feature | Camunda 8 Enterprise | Flowable + NATS |
|---------|---------------------|-----------------|
| Workflow engine license | $50K+/year | $0 (Apache 2.0) |
| Messaging license | Included (Zeebe) | $0 (NATS, Apache 2.0) |
| BPMN 2.0 | Full | Full |
| CMMN (Case Management) | No | Full |
| DMN (Decision) | Full | Full |
| High throughput | Zeebe partitioning | NATS JetStream |
| Push-based delivery | No (long-polling) | Yes (NATS native) |
| External worker pattern | Zeebe gRPC | NATS Request-Reply |
| Polyglot workers | Java, Go, C# | Any NATS client |
| Total annual cost | **$50K+** | **$0** |

## Features

- **Core NATS** — Pub/sub inbound and outbound event channels
- **JetStream** — Persistent messaging with ack/nack, exponential backoff (`nakWithDelay`), dead letter queue (JetStream primary + Core NATS fallback)
- **Request-Reply** — BPMN service tasks delegate work to external workers via NATS request-reply
- **Virtual Threads** — Java 21 virtual thread offloading for non-blocking I/O
- **Micrometer Metrics** — Counters for consume/ack/nak/dlq/publish + processing Timer
- **Structured Logging** — SLF4J `kv()` format with MDC trace_id propagation
- **Spring Boot Auto-Configuration** — Zero-config with `spring.nats.*` properties
- **Auth** — Username/password, token, credentials file, NKey

## Quick Start

### 1. Add dependency

```xml
<dependency>
    <groupId>com.3eai</groupId>
    <artifactId>flowable-nats-channel</artifactId>
    <version>0.1.0</version>
</dependency>
```

### 2. Configure NATS connection

```yaml
spring:
  nats:
    url: nats://localhost:4222
```

### 3. Define a channel

Create an inbound channel definition (JSON, deployed to Flowable):

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
    { "name": "queueGroup", "stringValue": "order-service" }
  ]
}
```

That's it. Flowable will subscribe to `order.new` on NATS and trigger process instances when events arrive.

## Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `spring.nats.url` | `nats://localhost:4222` | NATS server URL |
| `spring.nats.username` | — | Username auth |
| `spring.nats.password` | — | Password auth |
| `spring.nats.token` | — | Token auth |
| `spring.nats.credentials-file` | — | Credentials file path |
| `spring.nats.nkey-file` | — | NKey seed file |
| `spring.nats.connection-timeout` | `5s` | Connection timeout |
| `spring.nats.max-reconnects` | `-1` (infinite) | Max reconnection attempts |
| `spring.nats.reconnect-wait` | `2s` | Wait between reconnects |
| `spring.nats.tls.enabled` | `false` | Enable TLS |
| `spring.nats.tls.cert-file` | — | Client certificate |
| `spring.nats.tls.key-file` | — | Client private key |
| `spring.nats.tls.ca-file` | — | CA certificate |

## Channel Definitions

### Core NATS Outbound

```json
{
  "key": "orderOutboundChannel",
  "channelType": "outbound",
  "type": "nats",
  "serializerType": "json",
  "channelFields": [
    { "name": "subject", "stringValue": "order.completed" }
  ]
}
```

### JetStream Inbound (with DLQ)

```json
{
  "key": "orderJetStreamChannel",
  "channelType": "inbound",
  "type": "nats",
  "deserializerType": "json",
  "channelEventKeyDetection": { "fixedValue": "orderEvent" },
  "channelFields": [
    { "name": "subject", "stringValue": "order.new" },
    { "name": "jetstream", "stringValue": "true" },
    { "name": "durableName", "stringValue": "order-consumer" },
    { "name": "maxDeliver", "stringValue": "5" },
    { "name": "dlqSubject", "stringValue": "dlq.order.new" }
  ]
}
```

### JetStream Channel Fields

| Field | Default | Description |
|-------|---------|-------------|
| `jetstream` | `false` | Enable JetStream mode |
| `durableName` | — | Durable consumer name |
| `deliverPolicy` | `all` | `all`, `last`, `new` |
| `ackWait` | `30s` | Ack timeout |
| `maxDeliver` | `5` | Max retries before DLQ |
| `dlqSubject` | `dlq.{subject}` | Dead letter queue subject |
| `autoCreateStream` | `false` | Create stream if missing |
| `streamName` | — | Target stream name |

## Request-Reply (External Workers)

Dispatch work to external workers via NATS request-reply:

```xml
<serviceTask id="sendSms" name="Send SMS"
    flowable:delegateExpression="${natsRequestReply}">
  <extensionElements>
    <flowable:field name="subject" stringValue="task.send-sms" />
    <flowable:field name="timeout" stringValue="30s" />
    <flowable:field name="resultVariable" stringValue="smsResult" />
    <flowable:field name="payloadVariable" stringValue="smsPayload" />
  </extensionElements>
</serviceTask>
```

Workers can be written in **any language** with a NATS client:

**Go:**
```go
nc.QueueSubscribe("task.send-sms", "sms-workers", func(msg *nats.Msg) {
    result := processSMS(msg.Data)
    nc.Publish(msg.Reply, result)
})
```

**Java:**
```java
connection.createDispatcher().subscribe("task.send-sms", "sms-workers", msg -> {
    byte[] result = processSMS(msg.getData());
    connection.publish(msg.getReplyTo(), result);
});
```

**Python:**
```python
async def handler(msg):
    result = process_sms(msg.data)
    await nc.publish(msg.reply, result)

await nc.subscribe("task.send-sms", queue="sms-workers", cb=handler)
```

## Camunda 7 Support

For organizations running Camunda 7.x (including forks maintaining their own security patches), this project provides a complete NATS messaging layer via the `camunda-nats-channel` module.

### 1. Add dependency

```xml
<dependency>
    <groupId>com.3eai</groupId>
    <artifactId>camunda-nats-channel</artifactId>
    <version>0.1.0</version>
</dependency>
```

### 2. Configure subscriptions

```yaml
spring:
  nats:
    url: nats://localhost:4222
    camunda:
      subscriptions:
        - subject: order.new
          messageName: OrderReceived
          businessKeyHeader: X-Business-Key
        - subject: payment.completed
          messageName: PaymentConfirmed
          jetstream: true
          durableName: payment-consumer
          maxDeliver: 5
          dlqSubject: dlq.payment.completed
          autoCreateStream: true
          streamName: PAYMENTS
```

### 3. Inbound: NATS to Camunda message correlation

Messages arriving on configured NATS subjects are automatically correlated to waiting Camunda process instances using `RuntimeService.createMessageCorrelation()`.

- **Core NATS** -- `NatsMessageCorrelationSubscriber` for fire-and-forget pub/sub
- **JetStream** -- `JetStreamMessageCorrelationSubscriber` with ack/nack, exponential backoff, and DLQ

Variables set on the process instance:
- `natsPayload` -- raw message body (String)
- `natsSubject` -- NATS subject the message arrived on

### 4. Outbound: Camunda to NATS delegates

Use as `JavaDelegate` in service tasks:

**Core NATS Publish:**
```xml
<serviceTask id="notifyOrder" camunda:delegateExpression="${natsPublishDelegate}">
  <extensionElements>
    <camunda:field name="subject" stringValue="order.completed" />
    <camunda:field name="payloadVariable" stringValue="orderPayload" />
  </extensionElements>
</serviceTask>
```

**JetStream Publish:**
```xml
<serviceTask id="persistEvent" camunda:delegateExpression="${jetStreamPublishDelegate}">
  <extensionElements>
    <camunda:field name="subject" stringValue="audit.events" />
    <camunda:field name="payloadVariable" stringValue="auditPayload" />
  </extensionElements>
</serviceTask>
```

**Request-Reply (External Workers):**
```xml
<serviceTask id="sendSms" camunda:delegateExpression="${natsRequestReply}">
  <extensionElements>
    <camunda:field name="subject" stringValue="task.send-sms" />
    <camunda:field name="timeout" stringValue="30s" />
    <camunda:field name="resultVariable" stringValue="smsResult" />
    <camunda:field name="payloadVariable" stringValue="smsPayload" />
  </extensionElements>
</serviceTask>
```

### Camunda 7 Features

- **Message Correlation** -- Automatic NATS-to-Camunda message correlation with business key support
- **JetStream** -- Persistent messaging with ack/nack, exponential backoff, dead letter queue
- **Three Outbound Delegates** -- Core NATS publish, JetStream publish, Request-Reply
- **Virtual Threads** -- Java 21 virtual thread offloading
- **Spring Boot Auto-Configuration** -- `@ConditionalOnClass(ProcessEngine.class)`
- **Prototype-scoped Delegates** -- Thread-safe by design

## Requirements

- Java 21+
- Spring Boot 3.x
- Flowable 7.x and/or Camunda 7.x
- NATS 2.10+ (for JetStream `nakWithDelay`)
- `spring.threads.virtual.enabled: true` (recommended for optimal performance)

## Roadmap

| Phase | Status |
|-------|--------|
| Core NATS pub/sub | :white_check_mark: Complete |
| JetStream (persistent, DLQ, backoff) | :white_check_mark: Complete |
| Request-Reply (external workers) | :white_check_mark: Complete |
| Documentation & Release | :white_check_mark: Complete |
| Camunda 7 adapter | :white_check_mark: Complete |
| Key-Value Store integration | :crystal_ball: Planned |
| Object Store integration | :crystal_ball: Planned |

## Contributing

Contributions are welcome! Please open an issue first to discuss what you would like to change.

## License

[Apache License 2.0](LICENSE)

Copyright 2026 [3eAI Labs Ltd](https://3eai.com)
