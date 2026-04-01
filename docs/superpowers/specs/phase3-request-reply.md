# Phase 3: Flowable NATS Channel — Request-Reply Service Task

## Overview

NATS request-reply integration for Flowable service tasks. Enables BPMN processes to dispatch work to external workers (any language) via NATS and receive results synchronously from the process perspective. Workers use standard NATS `msg.respond()` — no SDK required.

**Scope:** `NatsRequestReplyDelegate` (JavaDelegate), auto-configuration bean, metrics, structured logging. No worker SDK — polyglot workers use native NATS request-reply protocol.

**Prerequisites:**
- Phase 1+2 complete (13 production classes, 46 tests passing)
- Java 21+ (virtual threads for sync request blocking)
- `spring.threads.virtual.enabled: true`

**License:** Apache 2.0

---

## 1. Architecture

### 1.1 Flow Diagram

```
BPMN Process                    NatsRequestReplyDelegate              NATS                    Worker (any language)
    │                                   │                              │                           │
    ├── ServiceTask executes ──────────▶│                              │                           │
    │   delegateExpression=             │── request(subject, data) ───▶│                           │
    │   "${natsRequestReply}"           │   (virtual thread, sync)     │── push to queue group ──▶│
    │                                   │                              │                           ├── process
    │                                   │                              │◀── msg.respond(result) ──┤
    │                                   │◀── reply received ───────────│                           │
    │◀── result → process variable ─────│                              │                           │
    ├── Next step...                    │                              │                           │
```

### 1.2 Class Structure

New classes (2 production + 2 test):

```
src/main/java/org/flowable/eventregistry/spring/nats/
└── requestreply/
    └── NatsRequestReplyDelegate.java          # JavaDelegate — request/reply orchestration

src/test/java/org/flowable/eventregistry/spring/nats/
└── requestreply/
    ├── NatsRequestReplyDelegateTest.java       # Unit: 6 cases
    └── NatsRequestReplyIntegrationTest.java    # Integration: 2 cases
```

Modified classes:

| Class | Change |
|-------|--------|
| `NatsChannelAutoConfiguration` | Add `NatsRequestReplyDelegate` bean |
| `NatsChannelMetrics` | Add 2 request-reply counters |

### 1.3 Design Principles

- **No new abstractions** — uses Flowable's standard `JavaDelegate` extension point
- **No worker SDK** — any NATS client in any language can be a worker via `msg.respond()`
- **Virtual thread safe** — `connection.request()` is sync but doesn't block OS threads
- **BPMN-native error handling** — timeouts throw `FlowableException`, catchable via boundary error events

---

## 2. NatsRequestReplyDelegate

### 2.1 Execution Steps

| # | Where | What Happens |
|---|-------|-------------|
| 1 | Flowable engine | Service task executes → calls `delegate.execute(execution)` |
| 2 | Delegate | Read `subject`, `timeout`, `resultVariable`, `payloadVariable` from BPMN fields |
| 3 | Delegate | Read request payload from process variable |
| 4 | Delegate | Extract traceId from execution variables → `MDC.put("trace_id", ...)` |
| 5 | Delegate | `connection.request(subject, data, timeout)` — sync on virtual thread |
| 6a | Reply received | Write reply body (UTF-8 String) to `resultVariable` process variable |
| 6b | Timeout | `FlowableException("timeout for subject '...' after 30s")` |
| 7 | Finally | `MDC.remove("trace_id")`, metrics increment |

### 2.2 Implementation

```java
package org.flowable.eventregistry.spring.nats.requestreply;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import io.nats.client.Connection;
import io.nats.client.Message;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.delegate.Expression;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.flowable.eventregistry.spring.nats.metrics.NatsChannelMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class NatsRequestReplyDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(NatsRequestReplyDelegate.class);

    private final Connection connection;
    private final NatsChannelMetrics metrics;

    private Expression subject;
    private Expression timeout;
    private Expression resultVariable;
    private Expression payloadVariable;

    public NatsRequestReplyDelegate(Connection connection, NatsChannelMetrics metrics) {
        this.connection = connection;
        this.metrics = metrics;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String subjectVal = getRequiredString(subject, execution, "subject");
        Duration timeoutVal = parseDuration(timeout, execution, Duration.ofSeconds(30));
        String resultVar = getString(resultVariable, execution, "natsReplyPayload");
        String payloadVar = getString(payloadVariable, execution, "natsRequestPayload");

        byte[] data = serializePayload(execution.getVariable(payloadVar));

        String traceId = (String) execution.getVariable("traceId");
        try {
            if (traceId != null) {
                MDC.put("trace_id", traceId);
            }

            log.debug("Sending NATS request",
                    kv("subject", subjectVal),
                    kv("timeout", timeoutVal),
                    kv("process_instance", execution.getProcessInstanceId()));

            Message reply = connection.request(subjectVal, data, timeoutVal);

            if (reply == null) {
                if (metrics != null) metrics.requestReplyErrorCount(subjectVal).increment();
                throw new FlowableException(
                        "NATS request-reply timeout for subject '" + subjectVal
                        + "' after " + timeoutVal);
            }

            String replyBody = new String(reply.getData(), StandardCharsets.UTF_8);
            execution.setVariable(resultVar, replyBody);

            if (metrics != null) metrics.requestReplyCount(subjectVal).increment();

            log.debug("NATS reply received",
                    kv("subject", subjectVal),
                    kv("result_variable", resultVar));

        } catch (FlowableException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (metrics != null) metrics.requestReplyErrorCount(subjectVal).increment();
            throw new FlowableException(
                    "NATS request-reply interrupted for subject '" + subjectVal + "'", e);
        } catch (Exception e) {
            if (metrics != null) metrics.requestReplyErrorCount(subjectVal).increment();
            throw new FlowableException(
                    "NATS request-reply failed for subject '" + subjectVal + "'", e);
        } finally {
            MDC.remove("trace_id");
        }
    }

    // --- Field helpers ---

    private String getRequiredString(Expression expr, DelegateExecution execution, String fieldName) {
        if (expr == null) {
            throw new FlowableException("NATS request-reply: '" + fieldName + "' is required");
        }
        String value = (String) expr.getValue(execution);
        if (value == null || value.isBlank()) {
            throw new FlowableException("NATS request-reply: '" + fieldName + "' resolved to blank");
        }
        return value;
    }

    private String getString(Expression expr, DelegateExecution execution, String defaultValue) {
        if (expr == null) return defaultValue;
        Object value = expr.getValue(execution);
        return value != null ? value.toString() : defaultValue;
    }

    private Duration parseDuration(Expression expr, DelegateExecution execution, Duration defaultValue) {
        if (expr == null) return defaultValue;
        Object value = expr.getValue(execution);
        if (value == null) return defaultValue;
        String str = value.toString().trim();
        // Support "30s", "5m", "1h" shorthand
        if (str.matches("\\d+s")) return Duration.ofSeconds(Long.parseLong(str.replace("s", "")));
        if (str.matches("\\d+m")) return Duration.ofMinutes(Long.parseLong(str.replace("m", "")));
        if (str.matches("\\d+h")) return Duration.ofHours(Long.parseLong(str.replace("h", "")));
        return Duration.parse(str);  // ISO-8601 fallback
    }

    private byte[] serializePayload(Object payload) {
        if (payload == null) return new byte[0];
        if (payload instanceof byte[] bytes) return bytes;
        if (payload instanceof String str) return str.getBytes(StandardCharsets.UTF_8);
        return payload.toString().getBytes(StandardCharsets.UTF_8);
    }

    // --- Expression setters (called by Flowable field injection) ---

    public void setSubject(Expression subject) { this.subject = subject; }
    public void setTimeout(Expression timeout) { this.timeout = timeout; }
    public void setResultVariable(Expression resultVariable) { this.resultVariable = resultVariable; }
    public void setPayloadVariable(Expression payloadVariable) { this.payloadVariable = payloadVariable; }
}
```

### 2.3 BPMN Configuration

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

<!-- Optional: catch timeout errors -->
<boundaryEvent id="smsTimeout" attachedToRef="sendSms">
  <errorEventDefinition />
</boundaryEvent>
```

### 2.4 BPMN Field Reference

| Field | Type | Default | Required | Description |
|-------|------|---------|----------|-------------|
| `subject` | Expression | — | Yes | NATS subject. Supports expressions: `task.${taskType}` |
| `timeout` | Expression | `"30s"` | No | Reply wait timeout. Formats: `30s`, `5m`, `PT30S` |
| `resultVariable` | Expression | `"natsReplyPayload"` | No | Process variable to store reply body |
| `payloadVariable` | Expression | `"natsRequestPayload"` | No | Process variable containing request payload |

### 2.5 Payload Handling

| Input (process variable) | Serialized as |
|--------------------------|---------------|
| `null` | Empty byte array |
| `byte[]` | Pass through |
| `String` | UTF-8 bytes |
| Other | `toString().getBytes(UTF_8)` |

Reply is always stored as `String` (UTF-8 decoded) in the process variable.

---

## 3. Metrics

### 3.1 New Counters (added to NatsChannelMetrics)

```java
// nats.requestreply.requests{subject}
public Counter requestReplyCount(String subject) {
    return Counter.builder("nats.requestreply.requests")
            .tag("subject", subject).register(registry);
}

// nats.requestreply.errors{subject}
public Counter requestReplyErrorCount(String subject) {
    return Counter.builder("nats.requestreply.errors")
            .tag("subject", subject).register(registry);
}
```

**Note:** These methods take a single `subject` parameter, unlike other `NatsChannelMetrics` methods that take `(subject, channel)`. This is intentional — request-reply has no "channel" concept. The delegate is a service task integration, not a channel adapter.

### 3.2 Monitoring

Grafana query for error rate: `rate(nats_requestreply_errors_total[5m]) / rate(nats_requestreply_requests_total[5m])`

---

## 4. Error Handling

| Error | Strategy |
|-------|----------|
| Reply timeout | `FlowableException("timeout for subject '...' after 30s")` → BPMN boundary error event |
| Connection unavailable | `FlowableException` wrapping cause |
| Missing `subject` field | `FlowableException("subject is required")` |
| Payload serialization failure | `FlowableException` wrapping cause |
| Worker returns error in reply body | Not adapter's concern — process logic checks `resultVariable` content |

All errors are `FlowableException` → Flowable error handling pipeline. Compliant with ERROR_HANDLING_GUIDELINE.

Log levels:
- Request sent: DEBUG
- Reply received: DEBUG
- Timeout: ERROR (via FlowableException, Flowable logs it)
- Connection error: ERROR

---

## 5. Auto-Configuration

```java
// NatsChannelAutoConfiguration — new bean
@Bean
@Scope("prototype")
public NatsRequestReplyDelegate natsRequestReply(
        Connection connection,
        @Autowired(required = false) NatsChannelMetrics metrics) {
    return new NatsRequestReplyDelegate(connection, metrics);
}
```

Bean name `natsRequestReply` matches BPMN `delegateExpression="${natsRequestReply}"`.

**Critical: Prototype scope required.** When Flowable uses `delegateExpression` with field injection (`Expression` setters), it calls setters on the bean instance before `execute()`. Under concurrent process execution, a singleton bean would have a race condition — Thread A sets `subject` to `"task.sms.send"`, Thread B overwrites it with `"task.ota.provision"` before Thread A calls `execute()`. Prototype scope ensures each execution gets a fresh delegate instance.

---

## 6. Subject Convention (Worker Documentation)

### 6.1 Naming Pattern

```
task.{domain}.{action}

Examples:
  task.sms.send
  task.ota.provision
  task.eir.check-device
  task.payment.process
```

### 6.2 Queue Group Convention

```
{domain}-{action}-workers

Examples:
  sms-send-workers
  ota-provision-workers
```

### 6.3 Worker Examples

**Go:**
```go
nc.QueueSubscribe("task.sms.send", "sms-send-workers", func(msg *nats.Msg) {
    var req SMSRequest
    json.Unmarshal(msg.Data, &req)
    result := sendSMS(req)
    reply, _ := json.Marshal(result)
    msg.Respond(reply)
})
```

**Java:**
```java
connection.createDispatcher().subscribe("task.sms.send", "sms-send-workers", msg -> {
    String request = new String(msg.getData(), StandardCharsets.UTF_8);
    String result = sendSMS(request);
    msg.respond(result.getBytes(StandardCharsets.UTF_8));
});
```

**Python:**
```python
async def handler(msg):
    request = json.loads(msg.data)
    result = send_sms(request)
    await msg.respond(json.dumps(result).encode())

await nc.subscribe("task.sms.send", queue="sms-send-workers", cb=handler)
```

---

## 7. Testing Strategy

### 7.1 Unit Tests

**NatsRequestReplyDelegateTest (6 cases):**
- `execute_sendsRequestAndStoresReply` — mock connection.request() returns reply → resultVariable set
- `execute_timeout_throwsFlowableException` — connection.request() returns null → FlowableException
- `execute_dynamicSubject_resolvesFromExecution` — Expression `task.${taskType}` resolved at runtime
- `execute_connectionError_throwsFlowableException` — connection throws → wrapped FlowableException
- `execute_nullPayload_sendsEmptyBody` — payload variable not set → empty byte array sent
- `execute_propagatesTraceId` — traceId execution variable → MDC during execution

### 7.2 Integration Tests

**NatsRequestReplyIntegrationTest (2 cases):**
- `requestReply_workerResponds_resultStored` — Testcontainers NATS, mock Go-style worker subscribes and responds, verify result in execution variable
- `requestReply_noWorker_timeoutThrowsException` — Testcontainers NATS, no worker, short timeout → FlowableException

### 7.3 Test Summary

| Category | Phase 1+2 | Phase 3 New | Total |
|----------|-----------|-------------|-------|
| Unit | 41 | 6 | 47 |
| Integration | 5 | 2 | 7 |
| **Total** | **46** | **8** | **54** |

---

## Appendix A: Key Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| 1 | JavaDelegate (not ActivityBehavior) | Most stable Flowable extension point. No deep coupling to engine internals. |
| 2 | No worker SDK | NATS request-reply is a standard protocol. Any NATS client can be a worker. YAGNI — Go SDK is a separate project if needed. |
| 3 | Sync request on virtual thread | Process semantics require synchronous result. Virtual thread makes sync call free. |
| 4 | Timeout → FlowableException | BPMN boundary error events catch timeouts. Retry logic in BPMN, not adapter. Separation of concerns. |
| 5 | Expression-based fields | Dynamic subjects (`task.${taskType}`), runtime-resolved from process variables. |
| 6 | Prototype-scoped bean | Expression field injection is not thread-safe on a singleton. Prototype scope prevents race conditions under concurrent execution. |

## Appendix B: Guidelines Compliance

| Guideline | Requirement | Status |
|-----------|-------------|--------|
| OBSERVABILITY_GUIDELINE | Structured logging | ✅ kv() format |
| OBSERVABILITY_GUIDELINE | trace_id in logs | ✅ MDC from execution variable |
| OBSERVABILITY_GUIDELINE | Metrics | ✅ request/error counters per subject |
| ERROR_HANDLING_GUIDELINE | Never silently fail | ✅ All errors → FlowableException |
| ERROR_HANDLING_GUIDELINE | Business violations = WARN | ✅ Timeout is ERROR (requires action) |
| CODING_GUIDELINES_JAVA | Package organization | ✅ requestreply/ package |

## Appendix C: Phase Roadmap

| Phase | Scope | Status |
|-------|-------|--------|
| 1 | Core NATS pub/sub adapter | ✅ Complete |
| 2 | JetStream + Metrics + Observability | ✅ Complete |
| 3 (this spec) | Request-Reply service task | In progress |
| 4 | Documentation & Release | Planned |
