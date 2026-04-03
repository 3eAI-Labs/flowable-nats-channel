# Phase 5: Camunda 7 Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor to multi-module Maven project, extract shared NATS infrastructure to `nats-core`, and build `camunda-nats-channel` module for Camunda 7.24 compatibility.

**Architecture:** Three Maven modules under a parent aggregator. `nats-core` holds shared NATS infrastructure (connection, metrics, headers, stream manager). `flowable-nats-channel` keeps existing Flowable code with updated imports. `camunda-nats-channel` uses Message Correlation for inbound and JavaDelegate for outbound. All modules share the same NATS connection, metrics, and JetStream patterns.

**Tech Stack:** Java 21+, Maven multi-module, Spring Boot 3.x, Flowable 7.1.0, Camunda 7.24.0, jnats 2.20+, JUnit 5, Mockito, Testcontainers

**Spec:** `docs/superpowers/specs/phase5-camunda7-compatibility.md`

**Critical invariant:** All 55 existing tests must pass after every refactoring task.

---

## File Structure

### Part 1: Multi-Module Refactoring

After refactoring, the project layout:

```
pom.xml                                               ← parent aggregator (was single-module pom)

nats-core/
├── pom.xml
└── src/main/java/com/threeai/nats/core/
    ├── NatsConnectionFactory.java                     ← NEW: extracted from AutoConfiguration
    ├── NatsHeaderUtils.java                           ← MOVED from o.f.e.s.nats
    ├── NatsProperties.java                            ← MOVED from o.f.e.s.nats.config
    ├── metrics/
    │   └── NatsChannelMetrics.java                    ← MOVED from o.f.e.s.nats.metrics
    └── jetstream/
        └── JetStreamStreamManager.java                ← MOVED from o.f.e.s.nats.jetstream

flowable-nats-channel/
├── pom.xml
├── src/main/java/org/flowable/eventregistry/spring/nats/
│   ├── (all Flowable-specific classes, imports updated)
│   └── config/
│       └── FlowableNatsAutoConfiguration.java         ← RENAMED + uses NatsConnectionFactory
├── src/main/resources/META-INF/spring/
│   └── org.springframework.boot.autoconfigure.AutoConfiguration.imports  ← UPDATED class name
└── src/test/java/...                                  ← all existing tests, imports updated

camunda-nats-channel/
├── pom.xml
└── src/main/java/com/threeai/nats/camunda/
    ├── inbound/
    │   ├── SubscriptionConfig.java
    │   ├── NatsMessageCorrelationSubscriber.java
    │   ├── JetStreamMessageCorrelationSubscriber.java
    │   └── NatsSubscriptionRegistrar.java
    ├── outbound/
    │   ├── NatsPublishDelegate.java
    │   ├── JetStreamPublishDelegate.java
    │   └── NatsRequestReplyDelegate.java
    └── config/
        ├── CamundaNatsProperties.java
        └── CamundaNatsAutoConfiguration.java
```

---

## PART 1: MULTI-MODULE REFACTORING

### Task 1: Create Parent POM + Module Skeletons

**Files:**
- Modify: `pom.xml` → convert to parent aggregator
- Create: `nats-core/pom.xml`
- Create: `flowable-nats-channel/pom.xml`

- [ ] **Step 1: Convert root pom.xml to parent aggregator**

Transform the current `pom.xml`:
- Change `<packaging>jar</packaging>` to `<packaging>pom</packaging>`
- Change `<artifactId>flowable-nats-channel</artifactId>` to `<artifactId>nats-channel-parent</artifactId>`
- Change `<name>` to `NATS Channel Adapter`
- Add `<modules>` section
- Move ALL dependencies to `<dependencyManagement>` (children will declare what they need)
- Keep properties, licenses, scm, developers, distributionManagement, profiles in parent
- Remove `<dependencies>` section (moved to children)
- Remove `<build><plugins>` section except compiler and surefire (moved to children as needed)

The parent pom should contain:
- `<modules>`: nats-core, flowable-nats-channel
- `<properties>`: all version properties + `<camunda.version>7.24.0</camunda.version>`
- `<dependencyManagement>`: all dependencies with versions (children inherit without version)
- `<build><pluginManagement>`: compiler, surefire, source, javadoc, gpg, nexus-staging versions
- licenses, scm, developers, distributionManagement, profiles (for release)

- [ ] **Step 2: Create nats-core/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.3eai</groupId>
        <artifactId>nats-channel-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>nats-core</artifactId>
    <name>NATS Core</name>
    <description>Shared NATS infrastructure: connection, metrics, headers, stream management</description>

    <dependencies>
        <dependency>
            <groupId>io.nats</groupId>
            <artifactId>jnats</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-core</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>net.logstash.logback</groupId>
            <artifactId>logstash-logback-encoder</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 3: Create flowable-nats-channel/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.3eai</groupId>
        <artifactId>nats-channel-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>flowable-nats-channel</artifactId>
    <name>Flowable NATS Channel Adapter</name>
    <description>NATS.io channel adapter for Flowable Event Registry</description>

    <dependencies>
        <dependency>
            <groupId>com.3eai</groupId>
            <artifactId>nats-core</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.flowable</groupId>
            <artifactId>flowable-event-registry-spring</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.flowable</groupId>
            <artifactId>flowable-engine</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 4: Move existing source to flowable-nats-channel module**

```bash
# Move all existing source to flowable module
mkdir -p flowable-nats-channel
mv src flowable-nats-channel/
mv src/main/resources flowable-nats-channel/src/main/resources 2>/dev/null || true
```

- [ ] **Step 5: Verify multi-module structure compiles**

Run: `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 mvn compile`
Expected: nats-core compiles (empty), flowable-nats-channel compiles (existing code)

Note: Tests may fail at this point because shared classes haven't moved yet. That's OK — we fix in Task 2.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: convert to multi-module Maven project (parent + nats-core + flowable)"
```

---

### Task 2: Move Shared Classes to nats-core

**Files:**
- Create: `nats-core/src/main/java/com/threeai/nats/core/NatsProperties.java`
- Create: `nats-core/src/main/java/com/threeai/nats/core/NatsHeaderUtils.java`
- Create: `nats-core/src/main/java/com/threeai/nats/core/NatsConnectionFactory.java`
- Create: `nats-core/src/main/java/com/threeai/nats/core/metrics/NatsChannelMetrics.java`
- Create: `nats-core/src/main/java/com/threeai/nats/core/jetstream/JetStreamStreamManager.java`
- Delete: originals from flowable-nats-channel
- Modify: all Flowable classes that import moved classes

- [ ] **Step 1: Create nats-core classes**

Copy each class to new package, update package declaration:

- `NatsProperties` → `com.threeai.nats.core.NatsProperties` (add Camunda subscription config class stub for later)
- `NatsHeaderUtils` → `com.threeai.nats.core.NatsHeaderUtils` (also make `extractHeader(Message, String)` a public static method here)
- `NatsChannelMetrics` → `com.threeai.nats.core.metrics.NatsChannelMetrics`
- `JetStreamStreamManager` → `com.threeai.nats.core.jetstream.JetStreamStreamManager`

Create new `NatsConnectionFactory`:
```java
package com.threeai.nats.core;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.io.IOException;
import io.nats.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NatsConnectionFactory {

    private static final Logger log = LoggerFactory.getLogger(NatsConnectionFactory.class);

    private NatsConnectionFactory() {}

    public static Connection create(NatsProperties props) throws IOException, InterruptedException {
        Options.Builder builder = new Options.Builder()
                .server(props.getUrl())
                .connectionTimeout(props.getConnectionTimeout())
                .maxReconnects(props.getMaxReconnects())
                .reconnectWait(props.getReconnectWait())
                .connectionListener((conn, event) -> {
                    switch (event) {
                        case CONNECTED -> log.info("NATS connected", kv("host", conn.getServerInfo().getHost()));
                        case RECONNECTED -> log.warn("NATS reconnected", kv("host", conn.getServerInfo().getHost()));
                        case DISCONNECTED -> log.warn("NATS disconnected");
                        case CLOSED -> log.info("NATS connection closed");
                        default -> log.debug("NATS connection event", kv("event", event.name()));
                    }
                })
                .errorListener(new ErrorListener() {
                    @Override public void errorOccurred(Connection conn, String error) {
                        log.error("NATS error", kv("error", error));
                    }
                    @Override public void exceptionOccurred(Connection conn, Exception exp) {
                        log.error("NATS exception", exp);
                    }
                    @Override public void slowConsumerDetected(Connection conn, Consumer consumer) {
                        log.warn("NATS slow consumer detected", kv("pending_messages", consumer.getPendingMessageCount()));
                    }
                });

        configureAuth(builder, props);
        return Nats.connect(builder.build());
    }

    private static void configureAuth(Options.Builder builder, NatsProperties props) {
        if (props.getCredentialsFile() != null) {
            builder.authHandler(Nats.credentials(props.getCredentialsFile()));
        } else if (props.getNkeyFile() != null) {
            builder.authHandler(Nats.credentials(null, props.getNkeyFile()));
        } else if (props.getToken() != null) {
            builder.token(props.getToken().toCharArray());
        } else if (props.getUsername() != null) {
            builder.userInfo(props.getUsername(), props.getPassword());
        }
    }
}
```

- [ ] **Step 2: Delete originals from flowable-nats-channel**

Remove:
- `flowable-nats-channel/src/main/java/org/flowable/eventregistry/spring/nats/config/NatsProperties.java`
- `flowable-nats-channel/src/main/java/org/flowable/eventregistry/spring/nats/NatsHeaderUtils.java`
- `flowable-nats-channel/src/main/java/org/flowable/eventregistry/spring/nats/metrics/NatsChannelMetrics.java`
- `flowable-nats-channel/src/main/java/org/flowable/eventregistry/spring/nats/jetstream/JetStreamStreamManager.java`

- [ ] **Step 3: Update all Flowable imports**

Every file that imported from the old locations must be updated:

| Old Import | New Import |
|-----------|-----------|
| `o.f.e.s.nats.config.NatsProperties` | `com.threeai.nats.core.NatsProperties` |
| `o.f.e.s.nats.NatsHeaderUtils` | `com.threeai.nats.core.NatsHeaderUtils` |
| `o.f.e.s.nats.metrics.NatsChannelMetrics` | `com.threeai.nats.core.metrics.NatsChannelMetrics` |
| `o.f.e.s.nats.jetstream.JetStreamStreamManager` | `com.threeai.nats.core.jetstream.JetStreamStreamManager` |

Files that need import updates (check each):
- `NatsChannelDefinitionProcessor.java` (metrics, stream manager)
- `NatsInboundEventChannelAdapter.java` (header utils if used)
- `NatsOutboundEventChannelAdapter.java` (header utils)
- `JetStreamInboundEventChannelAdapter.java` (metrics, header utils)
- `JetStreamOutboundEventChannelAdapter.java` (metrics, header utils)
- `NatsRequestReplyDelegate.java` (metrics)
- `NatsChannelAutoConfiguration.java` → rename to `FlowableNatsAutoConfiguration.java`, use `NatsConnectionFactory`
- All test files that reference moved classes

- [ ] **Step 4: Rename NatsChannelAutoConfiguration to FlowableNatsAutoConfiguration**

Rename the file and update:
- Class name: `FlowableNatsAutoConfiguration`
- Replace inline connection creation with `NatsConnectionFactory.create(props)`
- Update `AutoConfiguration.imports` file to reference new class name
- `@EnableConfigurationProperties(NatsProperties.class)` now imports from `com.threeai.nats.core`

- [ ] **Step 5: Move tests for shared classes to nats-core**

Move to `nats-core/src/test/java/com/threeai/nats/core/`:
- `NatsChannelMetricsTest.java` → `com.threeai.nats.core.metrics.NatsChannelMetricsTest`
- `JetStreamStreamManagerTest.java` → `com.threeai.nats.core.jetstream.JetStreamStreamManagerTest`

Update package declarations and imports in moved tests.

- [ ] **Step 6: Verify all tests pass**

Run: `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 mvn test`
Expected: 55 tests PASS across nats-core + flowable-nats-channel modules, BUILD SUCCESS

This is the critical checkpoint. If tests fail, fix imports before proceeding.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: extract shared classes to nats-core module (NatsProperties, HeaderUtils, Metrics, StreamManager, ConnectionFactory)"
```

---

## PART 2: CAMUNDA MODULE

### Task 3: Create Camunda Module Skeleton + SubscriptionConfig

**Files:**
- Create: `camunda-nats-channel/pom.xml`
- Create: `camunda-nats-channel/src/main/java/com/threeai/nats/camunda/inbound/SubscriptionConfig.java`
- Create: `camunda-nats-channel/src/main/java/com/threeai/nats/camunda/config/CamundaNatsProperties.java`
- Modify: parent `pom.xml` → add camunda module
- Create: `camunda-nats-channel/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [ ] **Step 1: Add camunda module to parent pom**

Add `<module>camunda-nats-channel</module>` to parent pom's `<modules>`.

Add Camunda dependency to parent's `<dependencyManagement>`:
```xml
<dependency>
    <groupId>org.camunda.bpm.springboot</groupId>
    <artifactId>camunda-bpm-spring-boot-starter</artifactId>
    <version>${camunda.version}</version>
</dependency>
<dependency>
    <groupId>org.camunda.bpm</groupId>
    <artifactId>camunda-engine</artifactId>
    <version>${camunda.version}</version>
</dependency>
```

- [ ] **Step 2: Create camunda-nats-channel/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.3eai</groupId>
        <artifactId>nats-channel-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>camunda-nats-channel</artifactId>
    <name>Camunda NATS Channel Adapter</name>
    <description>NATS.io adapter for Camunda 7 BPM Platform</description>

    <dependencies>
        <dependency>
            <groupId>com.3eai</groupId>
            <artifactId>nats-core</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.camunda.bpm</groupId>
            <artifactId>camunda-engine</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 3: Create SubscriptionConfig and CamundaNatsProperties**

`SubscriptionConfig.java`:
```java
package com.threeai.nats.camunda.inbound;

public class SubscriptionConfig {
    private String subject;
    private String messageName;
    private String businessKeyHeader;
    private String businessKeyVariable;
    private boolean jetstream;
    private String durableName;
    private int maxDeliver = 5;
    private String dlqSubject;
    private boolean autoCreateStream;
    private String streamName;
    // getters + setters for all fields
}
```

`CamundaNatsProperties.java`:
```java
package com.threeai.nats.camunda.config;

import java.util.ArrayList;
import java.util.List;
import com.threeai.nats.camunda.inbound.SubscriptionConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.nats.camunda")
public class CamundaNatsProperties {
    private List<SubscriptionConfig> subscriptions = new ArrayList<>();
    // getter + setter
}
```

- [ ] **Step 4: Create auto-config imports file**

Create `camunda-nats-channel/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
```
com.threeai.nats.camunda.config.CamundaNatsAutoConfiguration
```

- [ ] **Step 5: Verify compilation**

Run: `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 mvn compile`
Expected: All 3 modules compile

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: create camunda-nats-channel module skeleton with SubscriptionConfig"
```

---

### Task 4: Camunda Inbound — NatsMessageCorrelationSubscriber (TDD)

**Files:**
- Create: `camunda-nats-channel/src/test/java/com/threeai/nats/camunda/inbound/NatsMessageCorrelationSubscriberTest.java`
- Create: `camunda-nats-channel/src/main/java/com/threeai/nats/camunda/inbound/NatsMessageCorrelationSubscriber.java`

4 unit tests:
1. `handleMessage_correlatesSuccessfully` — mock RuntimeService, verify `correlateWithResult()` called
2. `handleMessage_correlationFails_logsError` — RuntimeService throws → error logged, no crash
3. `handleMessage_emptyBody_skips` — empty data → no correlation attempted
4. `handleMessage_propagatesTraceId` — X-Trace-Id header → MDC set and cleared

Implementation: subscribe to NATS subject, virtual thread offload, parse JSON payload, resolve business key (from header or payload field), call `runtimeService.createMessageCorrelation(messageName).processInstanceBusinessKey(key).setVariables(vars).correlateWithResult()`.

TDD: tests first → fail → implement → pass.

Commit: `git commit -m "feat: add NatsMessageCorrelationSubscriber for Camunda 7 inbound events"`

---

### Task 5: Camunda Inbound — JetStreamMessageCorrelationSubscriber (TDD)

**Files:**
- Create: `camunda-nats-channel/src/test/java/com/threeai/nats/camunda/inbound/JetStreamMessageCorrelationSubscriberTest.java`
- Create: `camunda-nats-channel/src/main/java/com/threeai/nats/camunda/inbound/JetStreamMessageCorrelationSubscriber.java`

6 unit tests:
1. `handleMessage_success_acksMessage` — correlation succeeds → `msg.ack()`
2. `handleMessage_correlationFails_naksWithDelay` — exception → `msg.nakWithDelay(backoff)`
3. `handleMessage_maxDeliverExceeded_publishesToDlq` — numDelivered > max → DLQ + ack
4. `handleMessage_dlqFails_stillAcks` — DLQ publish fails → still ack
5. `handleMessage_emptyBody_acks` — empty → ack + skip
6. `handleMessage_propagatesTraceId` — MDC trace propagation

Same ack/nack/DLQ/backoff pattern as Flowable's `JetStreamInboundEventChannelAdapter`. Different event delivery: `correlateWithResult()` instead of `eventRegistry.eventReceived()`.

TDD: tests first → fail → implement → pass.

Commit: `git commit -m "feat: add JetStreamMessageCorrelationSubscriber with ack/nack and DLQ for Camunda 7"`

---

### Task 6: Camunda Outbound — Delegates (TDD)

**Files:**
- Create: `camunda-nats-channel/src/test/java/com/threeai/nats/camunda/outbound/NatsPublishDelegateTest.java` (3 tests)
- Create: `camunda-nats-channel/src/main/java/com/threeai/nats/camunda/outbound/NatsPublishDelegate.java`
- Create: `camunda-nats-channel/src/test/java/com/threeai/nats/camunda/outbound/JetStreamPublishDelegateTest.java` (2 tests)
- Create: `camunda-nats-channel/src/main/java/com/threeai/nats/camunda/outbound/JetStreamPublishDelegate.java`
- Create: `camunda-nats-channel/src/test/java/com/threeai/nats/camunda/outbound/NatsRequestReplyDelegateTest.java` (4 tests)
- Create: `camunda-nats-channel/src/main/java/com/threeai/nats/camunda/outbound/NatsRequestReplyDelegate.java`

**NatsPublishDelegate** (3 tests):
- `execute_publishesMessage` — verify `connection.publish(NatsMessage)` with correct subject + data
- `execute_propagatesHeaders` — process variables → NATS headers
- `execute_missingSubject_throwsException` — no subject → `ProcessEngineException`

**JetStreamPublishDelegate** (2 tests):
- `execute_publishesToJetStream` — verify `jetStream.publish(NatsMessage)`
- `execute_publishFails_throwsException` — exception → `ProcessEngineException`

**NatsRequestReplyDelegate** (4 tests):
- `execute_sendsRequestAndStoresReply` — reply body → process variable
- `execute_timeout_throwsException` — `connection.request()` returns null → exception
- `execute_nullPayload_sendsEmptyBody` — null → empty byte[]
- `execute_propagatesTraceId` — MDC trace propagation

All use `org.camunda.bpm.engine.delegate.JavaDelegate`, `DelegateExecution`, `Expression`, `ProcessEngineException`. Same logic as Flowable delegates with Camunda imports.

TDD: tests first → fail → implement → pass.

Commit: `git commit -m "feat: add Camunda 7 outbound delegates (publish, JetStream publish, request-reply)"`

---

### Task 7: NatsSubscriptionRegistrar + CamundaNatsAutoConfiguration

**Files:**
- Create: `camunda-nats-channel/src/main/java/com/threeai/nats/camunda/inbound/NatsSubscriptionRegistrar.java`
- Create: `camunda-nats-channel/src/main/java/com/threeai/nats/camunda/config/CamundaNatsAutoConfiguration.java`
- Create: `camunda-nats-channel/src/test/java/com/threeai/nats/camunda/config/CamundaNatsAutoConfigurationTest.java` (3 tests)

**NatsSubscriptionRegistrar**: reads `CamundaNatsProperties.getSubscriptions()`, creates Core NATS or JetStream subscribers at `@PostConstruct`, unsubscribes at `@PreDestroy`. Implements `DisposableBean`.

**CamundaNatsAutoConfiguration**:
- `@ConditionalOnClass(org.camunda.bpm.engine.ProcessEngine.class)`
- Beans: Connection (via NatsConnectionFactory), JetStream, JetStreamStreamManager, NatsChannelMetrics (conditional), NatsSubscriptionRegistrar, prototype delegates (natsPublish, jetStreamPublish, natsRequestReply)

**Auto-config tests** (3):
- `autoConfig_withCamunda_createsBeans` — mock ProcessEngine on classpath → beans created
- `autoConfig_missingCamunda_doesNotLoad` — FilteredClassLoader removes ProcessEngine → no beans
- `autoConfig_propertiesBindCorrectly` — YAML properties → CamundaNatsProperties populated

Commit: `git commit -m "feat: add CamundaNatsAutoConfiguration and NatsSubscriptionRegistrar"`

---

### Task 8: Camunda Integration Tests

**Files:**
- Create: `camunda-nats-channel/src/test/java/com/threeai/nats/camunda/inbound/CamundaInboundIntegrationTest.java` (3 tests)
- Create: `camunda-nats-channel/src/test/java/com/threeai/nats/camunda/outbound/CamundaOutboundIntegrationTest.java` (2 tests)

Testcontainers `nats:2.10-alpine --jetstream` + mock `RuntimeService`.

**Inbound tests (3)**:
1. `inbound_coreNats_correlatesMessage` — publish to NATS → subscriber calls correlate on mock RuntimeService
2. `inbound_jetStream_acksAfterCorrelation` — JetStream publish → correlate → ack → no re-deliver
3. `inbound_jetStream_maxDeliverExceeded_dlq` — correlation always throws → DLQ subject receives message

**Outbound tests (2)**:
1. `outbound_publishDelegate_sendsToNats` — delegate publishes → subscriber receives message
2. `outbound_requestReply_workerResponds` — delegate sends request → worker responds → result stored

Commit: `git commit -m "test: add Camunda 7 integration tests with Testcontainers"`

---

### Task 9: Update README + Final Verification

**Files:**
- Modify: `README.md` — add Camunda 7 section
- Modify: `.github/workflows/ci.yml` — update for multi-module build

- [ ] **Step 1: Update README.md**

Add Camunda 7 section after Flowable Quick Start:

```markdown
## Camunda 7 Support

### Add dependency

\```xml
<dependency>
    <groupId>com.3eai</groupId>
    <artifactId>camunda-nats-channel</artifactId>
    <version>0.1.0</version>
</dependency>
\```

### Configure subscriptions

\```yaml
spring:
  nats:
    url: nats://localhost:4222
    camunda:
      subscriptions:
        - subject: event.order.created
          message-name: OrderCreated
          business-key-header: X-Business-Key
          jetstream: true
          durable-name: order-consumer
\```

### Outbound (BPMN service task)

\```xml
<serviceTask camunda:delegateExpression="${natsPublish}">
  <extensionElements>
    <camunda:field name="subject" stringValue="event.order.shipped" />
  </extensionElements>
</serviceTask>
\```
```

- [ ] **Step 2: Run full test suite across all modules**

Run: `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 mvn test`
Expected: ~82 tests PASS across all 3 modules, BUILD SUCCESS

- [ ] **Step 3: Push**

```bash
git add -A
git commit -m "docs: update README with Camunda 7 support section"
git push origin main
```
