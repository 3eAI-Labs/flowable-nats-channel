# Phase 3: Request-Reply Service Task Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add NATS request-reply support to Flowable via a JavaDelegate that enables BPMN service tasks to dispatch work to external workers and receive results.

**Architecture:** Single `NatsRequestReplyDelegate` (JavaDelegate) sends NATS request, waits for reply on virtual thread, stores result in process variable. Prototype-scoped Spring bean. Workers use standard NATS `msg.respond()` — no SDK. Two metrics counters added to existing `NatsChannelMetrics`.

**Tech Stack:** Java 21+, Spring Boot 3.x, Flowable 7.x (`JavaDelegate`, `Expression`), jnats 2.20+, JUnit 5, Mockito, Testcontainers

**Spec:** `docs/superpowers/specs/phase3-request-reply.md`

---

## File Structure

### New Files
```
src/main/java/org/flowable/eventregistry/spring/nats/requestreply/
└── NatsRequestReplyDelegate.java                    # JavaDelegate — request/reply

src/test/java/org/flowable/eventregistry/spring/nats/requestreply/
├── NatsRequestReplyDelegateTest.java                # Unit: 6 cases
└── NatsRequestReplyIntegrationTest.java             # Integration: 2 cases
```

### Modified Files
```
src/main/java/.../metrics/NatsChannelMetrics.java    # +2 request-reply counter methods
src/main/java/.../config/NatsChannelAutoConfiguration.java  # +1 prototype bean
```

---

### Task 0: Add flowable-engine dependency

**Files:**
- Modify: `pom.xml`

`JavaDelegate` and `DelegateExecution` are in `flowable-engine`, not `flowable-event-registry-spring`. We need this dependency for Phase 3.

- [ ] **Step 1: Add dependency to pom.xml**

Add in the `<dependencies>` section:

```xml
<!-- Flowable Engine (for JavaDelegate, DelegateExecution) -->
<dependency>
    <groupId>org.flowable</groupId>
    <artifactId>flowable-engine</artifactId>
    <version>${flowable.version}</version>
    <scope>provided</scope>
</dependency>
```

- [ ] **Step 2: Verify compilation and tests**

Run: `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 mvn test -q`
Expected: 46 tests PASS

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "chore: add flowable-engine dependency for JavaDelegate support"
```

---

### Task 1: Add Request-Reply Metrics to NatsChannelMetrics (TDD)

**Files:**
- Modify: `src/main/java/org/flowable/eventregistry/spring/nats/metrics/NatsChannelMetrics.java`
- Modify: `src/test/java/org/flowable/eventregistry/spring/nats/metrics/NatsChannelMetricsTest.java`

- [ ] **Step 1: Add failing test for request-reply counters**

Add to `NatsChannelMetricsTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 mvn test -Dtest=NatsChannelMetricsTest -q`
Expected: FAIL — `requestReplyCount` method does not exist

- [ ] **Step 3: Add methods to NatsChannelMetrics**

Add at the end of `NatsChannelMetrics.java`, before the closing brace:

```java
// Request-Reply metrics (single subject tag — no channel concept)
public Counter requestReplyCount(String subject) {
    return Counter.builder("nats.requestreply.requests")
            .tag("subject", subject).register(registry);
}

public Counter requestReplyErrorCount(String subject) {
    return Counter.builder("nats.requestreply.errors")
            .tag("subject", subject).register(registry);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 mvn test -Dtest=NatsChannelMetricsTest -q`
Expected: 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/flowable/eventregistry/spring/nats/metrics/NatsChannelMetrics.java \
        src/test/java/org/flowable/eventregistry/spring/nats/metrics/NatsChannelMetricsTest.java
git commit -m "feat: add request-reply counters to NatsChannelMetrics"
```

---

### Task 2: NatsRequestReplyDelegate (TDD)

**Files:**
- Create: `src/test/java/org/flowable/eventregistry/spring/nats/requestreply/NatsRequestReplyDelegateTest.java`
- Create: `src/main/java/org/flowable/eventregistry/spring/nats/requestreply/NatsRequestReplyDelegate.java`

- [ ] **Step 1: Write failing tests**

```java
package org.flowable.eventregistry.spring.nats.requestreply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import io.nats.client.Connection;
import io.nats.client.Message;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.delegate.Expression;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NatsRequestReplyDelegateTest {

    private Connection connection;
    private DelegateExecution execution;
    private NatsRequestReplyDelegate delegate;

    @BeforeEach
    void setUp() {
        connection = mock(Connection.class);
        execution = mock(DelegateExecution.class);
        when(execution.getProcessInstanceId()).thenReturn("proc-123");

        delegate = new NatsRequestReplyDelegate(connection, null);
    }

    @Test
    void execute_sendsRequestAndStoresReply() throws Exception {
        Expression subjectExpr = mockExpression("task.send-sms");
        Expression timeoutExpr = mockExpression("5s");
        Expression resultVarExpr = mockExpression("smsResult");
        Expression payloadVarExpr = mockExpression("smsPayload");

        delegate.setSubject(subjectExpr);
        delegate.setTimeout(timeoutExpr);
        delegate.setResultVariable(resultVarExpr);
        delegate.setPayloadVariable(payloadVarExpr);

        when(execution.getVariable("smsPayload")).thenReturn("{\"to\":\"+905551234567\"}");

        Message reply = mock(Message.class);
        when(reply.getData()).thenReturn("{\"status\":\"sent\"}".getBytes(StandardCharsets.UTF_8));
        when(connection.request(eq("task.send-sms"), any(byte[].class), eq(Duration.ofSeconds(5))))
                .thenReturn(reply);

        delegate.execute(execution);

        verify(execution).setVariable("smsResult", "{\"status\":\"sent\"}");
    }

    @Test
    void execute_timeout_throwsFlowableException() throws Exception {
        Expression subjectExpr = mockExpression("task.send-sms");
        delegate.setSubject(subjectExpr);

        when(execution.getVariable("natsRequestPayload")).thenReturn("test");
        when(connection.request(any(), any(byte[].class), any(Duration.class)))
                .thenReturn(null);

        assertThatThrownBy(() -> delegate.execute(execution))
                .isInstanceOf(FlowableException.class)
                .hasMessageContaining("timeout")
                .hasMessageContaining("task.send-sms");
    }

    @Test
    void execute_dynamicSubject_resolvesFromExecution() throws Exception {
        Expression subjectExpr = mock(Expression.class);
        when(subjectExpr.getValue(execution)).thenReturn("task.ota.provision");
        delegate.setSubject(subjectExpr);

        when(execution.getVariable("natsRequestPayload")).thenReturn("data");

        Message reply = mock(Message.class);
        when(reply.getData()).thenReturn("ok".getBytes(StandardCharsets.UTF_8));
        when(connection.request(eq("task.ota.provision"), any(byte[].class), any(Duration.class)))
                .thenReturn(reply);

        delegate.execute(execution);

        verify(connection).request(eq("task.ota.provision"), any(byte[].class), any(Duration.class));
    }

    @Test
    void execute_connectionError_throwsFlowableException() throws Exception {
        Expression subjectExpr = mockExpression("task.fail");
        delegate.setSubject(subjectExpr);

        when(execution.getVariable("natsRequestPayload")).thenReturn("data");
        when(connection.request(any(), any(byte[].class), any(Duration.class)))
                .thenThrow(new RuntimeException("connection lost"));

        assertThatThrownBy(() -> delegate.execute(execution))
                .isInstanceOf(FlowableException.class)
                .hasMessageContaining("task.fail")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void execute_nullPayload_sendsEmptyBody() throws Exception {
        Expression subjectExpr = mockExpression("task.empty");
        delegate.setSubject(subjectExpr);

        when(execution.getVariable("natsRequestPayload")).thenReturn(null);

        Message reply = mock(Message.class);
        when(reply.getData()).thenReturn("ok".getBytes(StandardCharsets.UTF_8));
        when(connection.request(eq("task.empty"), eq(new byte[0]), any(Duration.class)))
                .thenReturn(reply);

        delegate.execute(execution);

        verify(connection).request(eq("task.empty"), eq(new byte[0]), any(Duration.class));
    }

    @Test
    void execute_propagatesTraceId() throws Exception {
        Expression subjectExpr = mockExpression("task.trace");
        delegate.setSubject(subjectExpr);

        when(execution.getVariable("traceId")).thenReturn("trace-abc-123");
        when(execution.getVariable("natsRequestPayload")).thenReturn("data");

        Message reply = mock(Message.class);
        when(reply.getData()).thenReturn("ok".getBytes(StandardCharsets.UTF_8));
        when(connection.request(any(), any(byte[].class), any(Duration.class)))
                .thenReturn(reply);

        delegate.execute(execution);

        // MDC is thread-local and cleared in finally — verify no leak
        assertThat(org.slf4j.MDC.get("trace_id")).isNull();
    }

    private Expression mockExpression(String value) {
        Expression expr = mock(Expression.class);
        when(expr.getValue(any())).thenReturn(value);
        return expr;
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 mvn test -Dtest=NatsRequestReplyDelegateTest -q`
Expected: FAIL — class does not exist

- [ ] **Step 3: Write implementation**

Create `src/main/java/org/flowable/eventregistry/spring/nats/requestreply/NatsRequestReplyDelegate.java` with the exact implementation from spec Section 2.2. Key points:

- Implements `JavaDelegate`
- Constructor: `(Connection connection, NatsChannelMetrics metrics)` — metrics nullable
- 4 `Expression` fields with setters: `subject`, `timeout`, `resultVariable`, `payloadVariable`
- `execute()`: reads fields from expressions, serializes payload, calls `connection.request()`, stores reply
- Timeout: `connection.request()` returns null → `FlowableException`
- `InterruptedException`: restore interrupt flag, then throw `FlowableException`
- MDC trace propagation from `execution.getVariable("traceId")`
- Helper methods: `getRequiredString()`, `getString()`, `parseDuration()`, `serializePayload()`
- `parseDuration()` supports `30s`, `5m`, `1h` shorthand + ISO-8601 fallback

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 mvn test -Dtest=NatsRequestReplyDelegateTest -q`
Expected: 6 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/flowable/eventregistry/spring/nats/requestreply/NatsRequestReplyDelegate.java \
        src/test/java/org/flowable/eventregistry/spring/nats/requestreply/NatsRequestReplyDelegateTest.java
git commit -m "feat: add NatsRequestReplyDelegate JavaDelegate for BPMN service tasks"
```

---

### Task 3: Auto-Configuration Bean Registration

**Files:**
- Modify: `src/main/java/org/flowable/eventregistry/spring/nats/config/NatsChannelAutoConfiguration.java`

- [ ] **Step 1: Add prototype-scoped delegate bean**

Add to `NatsChannelAutoConfiguration.java`:

```java
import org.flowable.eventregistry.spring.nats.requestreply.NatsRequestReplyDelegate;
import org.springframework.context.annotation.Scope;

@Bean
@Scope("prototype")
public NatsRequestReplyDelegate natsRequestReply(
        Connection connection,
        @Autowired(required = false) NatsChannelMetrics metrics) {
    return new NatsRequestReplyDelegate(connection, metrics);
}
```

**Critical:** `@Scope("prototype")` is required. Flowable calls Expression setters on the bean before `execute()`. A singleton would have race conditions under concurrent process execution. Prototype scope creates a fresh instance per lookup.

**Note:** No `@ConditionalOnMissingBean` — prototype beans should always be created.

- [ ] **Step 2: Verify compilation and all tests pass**

Run: `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 mvn test -q`
Expected: All tests PASS (49 = 46 existing + 3 new)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/flowable/eventregistry/spring/nats/config/NatsChannelAutoConfiguration.java
git commit -m "feat: register NatsRequestReplyDelegate as prototype-scoped Spring bean"
```

---

### Task 4: Integration Tests

**Files:**
- Create: `src/test/java/org/flowable/eventregistry/spring/nats/requestreply/NatsRequestReplyIntegrationTest.java`

- [ ] **Step 1: Write integration tests**

```java
package org.flowable.eventregistry.spring.nats.requestreply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.delegate.Expression;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class NatsRequestReplyIntegrationTest {

    @Container
    static GenericContainer<?> natsContainer = new GenericContainer<>("nats:2.10-alpine")
            .withExposedPorts(4222);

    private Connection connection;
    private Connection workerConnection;

    @BeforeEach
    void setUp() throws Exception {
        String natsUrl = "nats://" + natsContainer.getHost() + ":" + natsContainer.getMappedPort(4222);
        connection = Nats.connect(new Options.Builder().server(natsUrl).build());
        workerConnection = Nats.connect(new Options.Builder().server(natsUrl).build());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
        if (workerConnection != null) workerConnection.close();
    }

    @Test
    void requestReply_workerResponds_resultStored() throws Exception {
        // Worker: subscribe and respond
        workerConnection.createDispatcher().subscribe("task.test.echo", "test-workers", msg -> {
            String request = new String(msg.getData(), StandardCharsets.UTF_8);
            msg.respond(("{\"echo\":\"" + request + "\"}").getBytes(StandardCharsets.UTF_8));
        });
        workerConnection.flush(java.time.Duration.ofSeconds(2));

        // Delegate
        NatsRequestReplyDelegate delegate = new NatsRequestReplyDelegate(connection, null);
        delegate.setSubject(mockExpression("task.test.echo"));
        delegate.setTimeout(mockExpression("5s"));
        delegate.setResultVariable(mockExpression("testResult"));
        delegate.setPayloadVariable(mockExpression("testPayload"));

        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.getProcessInstanceId()).thenReturn("test-proc-1");
        when(execution.getVariable("testPayload")).thenReturn("hello");

        delegate.execute(execution);

        verify(execution).setVariable("testResult", "{\"echo\":\"hello\"}");
    }

    @Test
    void requestReply_noWorker_timeoutThrowsException() {
        NatsRequestReplyDelegate delegate = new NatsRequestReplyDelegate(connection, null);
        delegate.setSubject(mockExpression("task.nobody.listening"));
        delegate.setTimeout(mockExpression("1s"));

        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.getProcessInstanceId()).thenReturn("test-proc-2");
        when(execution.getVariable("natsRequestPayload")).thenReturn("data");

        assertThatThrownBy(() -> delegate.execute(execution))
                .isInstanceOf(FlowableException.class)
                .hasMessageContaining("timeout")
                .hasMessageContaining("task.nobody.listening");
    }

    private Expression mockExpression(String value) {
        Expression expr = mock(Expression.class);
        when(expr.getValue(any())).thenReturn(value);
        return expr;
    }
}
```

- [ ] **Step 2: Run integration tests**

Run: `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 mvn test -Dtest=NatsRequestReplyIntegrationTest -q`
Expected: 2 tests PASS

- [ ] **Step 3: Commit**

```bash
git add src/test/java/org/flowable/eventregistry/spring/nats/requestreply/NatsRequestReplyIntegrationTest.java
git commit -m "test: add NATS request-reply integration tests with Testcontainers"
```

---

### Task 5: Full Test Suite Verification

**Files:** None (verification only)

- [ ] **Step 1: Run all tests**

Run: `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 mvn test`
Expected: 54 tests PASS (46 Phase 1+2 + 8 Phase 3), BUILD SUCCESS

- [ ] **Step 2: Verify test counts**

Run: `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 mvn test 2>&1 | grep "Tests run:"`

Expected new entries:
```
NatsChannelMetricsTest:                     3  (was 2, +1 new)
NatsRequestReplyDelegateTest:               6  (new)
NatsRequestReplyIntegrationTest:            2  (new)
```

- [ ] **Step 3: Verify file structure**

Run: `find src -name "*.java" -path "*/requestreply/*" | sort`
Expected:
```
src/main/java/.../requestreply/NatsRequestReplyDelegate.java
src/test/java/.../requestreply/NatsRequestReplyDelegateTest.java
src/test/java/.../requestreply/NatsRequestReplyIntegrationTest.java
```

- [ ] **Step 4: Push to remote**

```bash
git push origin main
```
