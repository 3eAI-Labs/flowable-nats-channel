# Phase 1: Flowable NATS Channel — Core Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Flowable Event Registry channel adapter that enables NATS Core pub/sub messaging for inbound and outbound events.

**Architecture:** Lean Adapter approach — mirrors Kafka adapter's Flowable contract compliance (implements `ChannelModelProcessor`, `InboundEventChannelAdapter`, `OutboundEventChannelAdapter`) while stripping NATS-irrelevant complexity. Single Spring Boot auto-configuration class manages both NATS Connection and processor beans.

**Tech Stack:** Java 17+, Spring Boot 3.x, Flowable 7.x Event Registry, jnats 2.19+, JUnit 5, Mockito, Testcontainers

**Spec:** `docs/superpowers/specs/phase1-core-adapter.md`

---

## Important: Corrected Flowable API Signatures

The design spec used simplified interface signatures. The actual Flowable 7.x interfaces are:

```java
// ChannelModelProcessor — 4 methods
boolean canProcess(ChannelModel channelModel);
boolean canProcessIfChannelModelAlreadyRegistered(ChannelModel channelModel);
void registerChannelModel(ChannelModel channelModel, String tenantId,
    EventRegistry eventRegistry, EventRepositoryService eventRepositoryService,
    boolean fallbackToDefaultTenant);
void unregisterChannelModel(ChannelModel channelModel, String tenantId,
    EventRepositoryService eventRepositoryService);

// InboundEventChannelAdapter — 2 setters (no subscribe method in interface)
void setInboundChannelModel(InboundChannelModel inboundChannelModel);
void setEventRegistry(EventRegistry eventRegistry);

// OutboundEventChannelAdapter<T> — sendEvent with headers
default void sendEvent(OutboundEvent<T> event) { sendEvent(event.getBody(), event.getHeaders()); }
void sendEvent(T rawEvent, Map<String, Object> headerMap);

// InboundEvent — 3 methods
Object getRawEvent();
Object getBody();
Map<String, Object> getHeaders();

// Inbound event delivery — via EventRegistry
eventRegistry.eventReceived(inboundChannelModel, inboundEvent);
```

The Kafka adapter uses `instanceof` checks in `canProcess()`, not `type` string matching. Channel models are typed subclasses with direct fields (not generic `channelFields`).

---

## File Structure

```
flowable-nats-channel/
├── pom.xml                                                          # Maven build with all dependencies
├── src/
│   ├── main/
│   │   ├── java/org/flowable/eventregistry/spring/nats/
│   │   │   ├── channel/
│   │   │   │   ├── NatsInboundChannelModel.java                     # Extends InboundChannelModel — subject, queueGroup, jetstream fields
│   │   │   │   └── NatsOutboundChannelModel.java                    # Extends OutboundChannelModel — subject, jetstream fields
│   │   │   ├── NatsChannelDefinitionProcessor.java                  # Implements ChannelModelProcessor — registers/unregisters channels
│   │   │   ├── NatsInboundEventChannelAdapter.java                  # Implements InboundEventChannelAdapter — subscribes to NATS, delivers to Flowable
│   │   │   ├── NatsOutboundEventChannelAdapter.java                 # Implements OutboundEventChannelAdapter<String> — publishes to NATS
│   │   │   ├── NatsInboundEvent.java                                # Implements InboundEvent — wraps NATS Message
│   │   │   └── config/
│   │   │       ├── NatsChannelAutoConfiguration.java                # Spring Boot auto-config — Connection + Processor beans
│   │   │       └── NatsProperties.java                              # @ConfigurationProperties for spring.nats.*
│   │   └── resources/
│   │       └── META-INF/
│   │           └── spring/
│   │               └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
│   └── test/
│       └── java/org/flowable/eventregistry/spring/nats/
│           ├── NatsChannelDefinitionProcessorTest.java              # Unit: processor logic (8 cases)
│           ├── NatsInboundEventChannelAdapterTest.java              # Unit: inbound adapter (4 cases)
│           ├── NatsOutboundEventChannelAdapterTest.java             # Unit: outbound adapter (2 cases)
│           ├── NatsInboundChannelIntegrationTest.java               # Integration: NATS→Flowable with Testcontainers (2 cases)
│           ├── NatsOutboundChannelIntegrationTest.java              # Integration: Flowable→NATS with Testcontainers (1 case)
│           └── config/
│               └── NatsChannelAutoConfigurationTest.java            # Unit: conditional bean loading (3 cases)
```

---

### Task 1: Project Scaffolding (pom.xml + directory structure)

**Files:**
- Create: `pom.xml`
- Create: `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [ ] **Step 1: Create pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.3eai</groupId>
    <artifactId>flowable-nats-channel</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <name>Flowable NATS Channel Adapter</name>
    <description>NATS.io channel adapter for Flowable Event Registry</description>
    <url>https://github.com/3eai-labs/flowable-nats-channel</url>

    <licenses>
        <license>
            <name>Apache License, Version 2.0</name>
            <url>https://www.apache.org/licenses/LICENSE-2.0</url>
        </license>
    </licenses>

    <properties>
        <java.version>17</java.version>
        <maven.compiler.source>${java.version}</maven.compiler.source>
        <maven.compiler.target>${java.version}</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>

        <spring-boot.version>3.3.6</spring-boot.version>
        <flowable.version>7.1.0</flowable.version>
        <jnats.version>2.20.5</jnats.version>
        <testcontainers.version>1.20.4</testcontainers.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring-boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>org.testcontainers</groupId>
                <artifactId>testcontainers-bom</artifactId>
                <version>${testcontainers.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- Flowable Event Registry -->
        <dependency>
            <groupId>org.flowable</groupId>
            <artifactId>flowable-event-registry-spring</artifactId>
            <version>${flowable.version}</version>
            <scope>provided</scope>
        </dependency>

        <!-- NATS Java Client -->
        <dependency>
            <groupId>io.nats</groupId>
            <artifactId>jnats</artifactId>
            <version>${jnats.version}</version>
        </dependency>

        <!-- Spring Boot Auto-Configuration -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- SLF4J -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>

        <!-- Test -->
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
        <dependency>
            <groupId>org.flowable</groupId>
            <artifactId>flowable-event-registry-spring</artifactId>
            <version>${flowable.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
                <configuration>
                    <source>${java.version}</source>
                    <target>${java.version}</target>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.2</version>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create auto-configuration imports file**

Create `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
org.flowable.eventregistry.spring.nats.config.NatsChannelAutoConfiguration
```

- [ ] **Step 3: Verify project compiles**

Run: `mvn compile -q`
Expected: BUILD SUCCESS (no source files yet, but dependencies resolve)

- [ ] **Step 4: Commit**

```bash
git add pom.xml src/main/resources/
git commit -m "chore: scaffold Maven project with Flowable, jnats, and Testcontainers dependencies"
```

---

### Task 2: Channel Models (NatsInboundChannelModel + NatsOutboundChannelModel)

**Files:**
- Create: `src/main/java/org/flowable/eventregistry/spring/nats/channel/NatsInboundChannelModel.java`
- Create: `src/main/java/org/flowable/eventregistry/spring/nats/channel/NatsOutboundChannelModel.java`

- [ ] **Step 1: Create NatsInboundChannelModel**

```java
package org.flowable.eventregistry.spring.nats.channel;

import org.flowable.eventregistry.model.InboundChannelModel;

public class NatsInboundChannelModel extends InboundChannelModel {

    private String subject;
    private String queueGroup;
    private boolean jetstream;

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getQueueGroup() {
        return queueGroup;
    }

    public void setQueueGroup(String queueGroup) {
        this.queueGroup = queueGroup;
    }

    public boolean isJetstream() {
        return jetstream;
    }

    public void setJetstream(boolean jetstream) {
        this.jetstream = jetstream;
    }
}
```

- [ ] **Step 2: Create NatsOutboundChannelModel**

```java
package org.flowable.eventregistry.spring.nats.channel;

import org.flowable.eventregistry.model.OutboundChannelModel;

public class NatsOutboundChannelModel extends OutboundChannelModel {

    private String subject;
    private boolean jetstream;

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public boolean isJetstream() {
        return jetstream;
    }

    public void setJetstream(boolean jetstream) {
        this.jetstream = jetstream;
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/flowable/eventregistry/spring/nats/channel/
git commit -m "feat: add NatsInboundChannelModel and NatsOutboundChannelModel"
```

---

### Task 3: NatsInboundEvent

**Files:**
- Create: `src/main/java/org/flowable/eventregistry/spring/nats/NatsInboundEvent.java`

- [ ] **Step 1: Create NatsInboundEvent**

```java
package org.flowable.eventregistry.spring.nats;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import io.nats.client.Message;
import org.flowable.eventregistry.api.InboundEvent;

public class NatsInboundEvent implements InboundEvent {

    private final Message rawMessage;
    private final String body;
    private final Map<String, Object> headers;

    public NatsInboundEvent(Message message) {
        this.rawMessage = message;
        this.body = message.getData() != null
                ? new String(message.getData(), StandardCharsets.UTF_8)
                : null;
        this.headers = extractHeaders(message);
    }

    @Override
    public Object getRawEvent() {
        return rawMessage;
    }

    @Override
    public Object getBody() {
        return body;
    }

    @Override
    public Map<String, Object> getHeaders() {
        return headers;
    }

    private static Map<String, Object> extractHeaders(Message message) {
        if (message.getHeaders() == null || message.getHeaders().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> headers = new HashMap<>();
        for (String key : message.getHeaders().keySet()) {
            // NATS headers can have multiple values; take the last one (most recent)
            headers.put(key, message.getHeaders().getLast(key));
        }
        return Collections.unmodifiableMap(headers);
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/flowable/eventregistry/spring/nats/NatsInboundEvent.java
git commit -m "feat: add NatsInboundEvent wrapping NATS Message to Flowable InboundEvent"
```

---

### Task 4: NatsOutboundEventChannelAdapter (test-first)

**Files:**
- Create: `src/test/java/org/flowable/eventregistry/spring/nats/NatsOutboundEventChannelAdapterTest.java`
- Create: `src/main/java/org/flowable/eventregistry/spring/nats/NatsOutboundEventChannelAdapter.java`

- [ ] **Step 1: Write failing tests**

```java
package org.flowable.eventregistry.spring.nats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import io.nats.client.Connection;
import org.flowable.common.engine.api.FlowableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NatsOutboundEventChannelAdapterTest {

    private Connection connection;
    private NatsOutboundEventChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        connection = mock(Connection.class);
        adapter = new NatsOutboundEventChannelAdapter(connection, "order.completed");
    }

    @Test
    void sendEvent_publishesMessage() {
        when(connection.getStatus()).thenReturn(Connection.Status.CONNECTED);

        adapter.sendEvent("{\"orderId\":123}", Collections.emptyMap());

        verify(connection).publish(eq("order.completed"),
                eq("{\"orderId\":123}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void sendEvent_connectionClosed_throwsFlowableException() {
        when(connection.getStatus()).thenReturn(Connection.Status.CLOSED);

        assertThatThrownBy(() -> adapter.sendEvent("{\"orderId\":123}", Collections.emptyMap()))
                .isInstanceOf(FlowableException.class)
                .hasMessageContaining("order.completed");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl . -Dtest=NatsOutboundEventChannelAdapterTest -q`
Expected: FAIL — `NatsOutboundEventChannelAdapter` class does not exist

- [ ] **Step 3: Write minimal implementation**

```java
package org.flowable.eventregistry.spring.nats;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import io.nats.client.Connection;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.eventregistry.api.OutboundEventChannelAdapter;

public class NatsOutboundEventChannelAdapter implements OutboundEventChannelAdapter<String> {

    private final Connection connection;
    private final String subject;

    public NatsOutboundEventChannelAdapter(Connection connection, String subject) {
        this.connection = connection;
        this.subject = subject;
    }

    @Override
    public void sendEvent(String rawEvent, Map<String, Object> headerMap) {
        Connection.Status status = connection.getStatus();
        if (status == Connection.Status.CLOSED || status == Connection.Status.DISCONNECTED) {
            throw new FlowableException(
                    "NATS outbound channel: connection not available for subject '" + subject
                    + "' (status: " + status + ")");
        }
        connection.publish(subject, rawEvent.getBytes(StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl . -Dtest=NatsOutboundEventChannelAdapterTest -q`
Expected: 2 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/flowable/eventregistry/spring/nats/NatsOutboundEventChannelAdapter.java \
        src/test/java/org/flowable/eventregistry/spring/nats/NatsOutboundEventChannelAdapterTest.java
git commit -m "feat: add NatsOutboundEventChannelAdapter with TDD"
```

---

### Task 5: NatsInboundEventChannelAdapter (test-first)

**Files:**
- Create: `src/test/java/org/flowable/eventregistry/spring/nats/NatsInboundEventChannelAdapterTest.java`
- Create: `src/main/java/org/flowable/eventregistry/spring/nats/NatsInboundEventChannelAdapter.java`

- [ ] **Step 1: Write failing tests**

```java
package org.flowable.eventregistry.spring.nats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import io.nats.client.Subscription;
import io.nats.client.impl.Headers;
import org.flowable.eventregistry.api.EventRegistry;
import org.flowable.eventregistry.spring.nats.channel.NatsInboundChannelModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NatsInboundEventChannelAdapterTest {

    private Connection connection;
    private Dispatcher dispatcher;
    private EventRegistry eventRegistry;
    private NatsInboundChannelModel channelModel;
    private NatsInboundEventChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        connection = mock(Connection.class);
        dispatcher = mock(Dispatcher.class);
        eventRegistry = mock(EventRegistry.class);

        when(connection.createDispatcher()).thenReturn(dispatcher);
        when(dispatcher.subscribe(any(), any())).thenReturn(mock(Subscription.class));
        when(dispatcher.subscribe(any(), any(), any())).thenReturn(mock(Subscription.class));

        channelModel = new NatsInboundChannelModel();
        channelModel.setKey("testChannel");
        channelModel.setSubject("order.new");

        adapter = new NatsInboundEventChannelAdapter(connection, "order.new", null);
        adapter.setInboundChannelModel(channelModel);
        adapter.setEventRegistry(eventRegistry);
    }

    @Test
    void subscribe_createsDispatcherAndSubscribes() {
        adapter.subscribe();

        verify(connection).createDispatcher();
        verify(dispatcher).subscribe(eq("order.new"), any());
    }

    @Test
    void subscribe_withQueueGroup_subscribesWithQueueGroup() {
        adapter = new NatsInboundEventChannelAdapter(connection, "order.new", "order-service");
        adapter.setInboundChannelModel(channelModel);
        adapter.setEventRegistry(eventRegistry);

        adapter.subscribe();

        verify(dispatcher).subscribe(eq("order.new"), eq("order-service"), any());
    }

    @Test
    void handleMessage_validMessage_triggersEventReceived() {
        Message message = mock(Message.class);
        when(message.getData()).thenReturn("{\"orderId\":1}".getBytes(StandardCharsets.UTF_8));
        when(message.getHeaders()).thenReturn(null);
        when(message.getSubject()).thenReturn("order.new");

        adapter.handleMessage(message);

        verify(eventRegistry).eventReceived(eq(channelModel), any(NatsInboundEvent.class));
    }

    @Test
    void handleMessage_emptyBody_skipsWithoutTrigger() {
        Message message = mock(Message.class);
        when(message.getData()).thenReturn(new byte[0]);
        when(message.getSubject()).thenReturn("order.new");

        adapter.handleMessage(message);

        verify(eventRegistry, never()).eventReceived(any(), any(NatsInboundEvent.class));
    }

    @Test
    void handleMessage_processingError_continuesSubscription() {
        // First message causes exception in eventRegistry
        Message badMessage = mock(Message.class);
        when(badMessage.getData()).thenReturn("{\"bad\":true}".getBytes(StandardCharsets.UTF_8));
        when(badMessage.getHeaders()).thenReturn(null);
        when(badMessage.getSubject()).thenReturn("order.new");
        org.mockito.Mockito.doThrow(new RuntimeException("simulated error"))
                .when(eventRegistry).eventReceived(any(), any(NatsInboundEvent.class));

        // Should not throw — adapter catches and logs
        adapter.handleMessage(badMessage);

        // Reset mock to allow normal processing
        org.mockito.Mockito.reset(eventRegistry);

        // Second message should still be processed (subscription not killed)
        Message goodMessage = mock(Message.class);
        when(goodMessage.getData()).thenReturn("{\"good\":true}".getBytes(StandardCharsets.UTF_8));
        when(goodMessage.getHeaders()).thenReturn(null);
        when(goodMessage.getSubject()).thenReturn("order.new");

        adapter.handleMessage(goodMessage);

        verify(eventRegistry).eventReceived(eq(channelModel), any(NatsInboundEvent.class));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl . -Dtest=NatsInboundEventChannelAdapterTest -q`
Expected: FAIL — `NatsInboundEventChannelAdapter` class does not exist

- [ ] **Step 3: Write minimal implementation**

```java
package org.flowable.eventregistry.spring.nats;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import org.flowable.eventregistry.api.EventRegistry;
import org.flowable.eventregistry.api.InboundEventChannelAdapter;
import org.flowable.eventregistry.model.InboundChannelModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NatsInboundEventChannelAdapter implements InboundEventChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(NatsInboundEventChannelAdapter.class);

    private final Connection connection;
    private final String subject;
    private final String queueGroup;

    private InboundChannelModel inboundChannelModel;
    private EventRegistry eventRegistry;
    private Dispatcher dispatcher;

    public NatsInboundEventChannelAdapter(Connection connection, String subject, String queueGroup) {
        this.connection = connection;
        this.subject = subject;
        this.queueGroup = queueGroup;
    }

    @Override
    public void setInboundChannelModel(InboundChannelModel inboundChannelModel) {
        this.inboundChannelModel = inboundChannelModel;
    }

    @Override
    public void setEventRegistry(EventRegistry eventRegistry) {
        this.eventRegistry = eventRegistry;
    }

    public void subscribe() {
        this.dispatcher = connection.createDispatcher();
        if (queueGroup != null && !queueGroup.isBlank()) {
            dispatcher.subscribe(subject, queueGroup, this::handleMessage);
        } else {
            dispatcher.subscribe(subject, this::handleMessage);
        }
        log.info("NATS inbound channel '{}': subscribed to subject '{}'{}",
                inboundChannelModel.getKey(), subject,
                queueGroup != null ? " with queue group '" + queueGroup + "'" : "");
    }

    public void unsubscribe() {
        if (dispatcher != null) {
            try {
                dispatcher.drain(java.time.Duration.ofSeconds(5));
            } catch (Exception e) {
                log.warn("NATS inbound channel '{}': error draining dispatcher",
                        inboundChannelModel.getKey(), e);
            }
            connection.closeDispatcher(dispatcher);
            dispatcher = null;
            log.info("NATS inbound channel '{}': unsubscribed from subject '{}'",
                    inboundChannelModel.getKey(), subject);
        }
    }

    void handleMessage(Message message) {
        if (message.getData() == null || message.getData().length == 0) {
            log.warn("NATS inbound channel '{}': empty message on subject '{}', skipping",
                    inboundChannelModel.getKey(), message.getSubject());
            return;
        }

        try {
            NatsInboundEvent event = new NatsInboundEvent(message);
            eventRegistry.eventReceived(inboundChannelModel, event);
        } catch (Exception e) {
            log.error("NATS inbound channel '{}': failed to process message on subject '{}'",
                    inboundChannelModel.getKey(), message.getSubject(), e);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl . -Dtest=NatsInboundEventChannelAdapterTest -q`
Expected: 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/flowable/eventregistry/spring/nats/NatsInboundEventChannelAdapter.java \
        src/test/java/org/flowable/eventregistry/spring/nats/NatsInboundEventChannelAdapterTest.java
git commit -m "feat: add NatsInboundEventChannelAdapter with TDD"
```

---

### Task 6: NatsChannelDefinitionProcessor (test-first)

**Files:**
- Create: `src/test/java/org/flowable/eventregistry/spring/nats/NatsChannelDefinitionProcessorTest.java`
- Create: `src/main/java/org/flowable/eventregistry/spring/nats/NatsChannelDefinitionProcessor.java`

- [ ] **Step 1: Write failing tests**

```java
package org.flowable.eventregistry.spring.nats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Subscription;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.eventregistry.api.EventRegistry;
import org.flowable.eventregistry.api.EventRepositoryService;
import org.flowable.eventregistry.model.ChannelModel;
import org.flowable.eventregistry.model.InboundChannelModel;
import org.flowable.eventregistry.spring.nats.channel.NatsInboundChannelModel;
import org.flowable.eventregistry.spring.nats.channel.NatsOutboundChannelModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NatsChannelDefinitionProcessorTest {

    private Connection connection;
    private EventRegistry eventRegistry;
    private EventRepositoryService eventRepositoryService;
    private NatsChannelDefinitionProcessor processor;

    @BeforeEach
    void setUp() {
        connection = mock(Connection.class);
        eventRegistry = mock(EventRegistry.class);
        eventRepositoryService = mock(EventRepositoryService.class);

        Dispatcher dispatcher = mock(Dispatcher.class);
        when(connection.createDispatcher()).thenReturn(dispatcher);
        when(dispatcher.subscribe(any(), any())).thenReturn(mock(Subscription.class));
        when(dispatcher.subscribe(any(), any(), any())).thenReturn(mock(Subscription.class));

        processor = new NatsChannelDefinitionProcessor(connection);
    }

    @Test
    void canProcess_natsInboundModel_returnsTrue() {
        NatsInboundChannelModel model = new NatsInboundChannelModel();
        assertThat(processor.canProcess(model)).isTrue();
    }

    @Test
    void canProcess_natsOutboundModel_returnsTrue() {
        NatsOutboundChannelModel model = new NatsOutboundChannelModel();
        assertThat(processor.canProcess(model)).isTrue();
    }

    @Test
    void canProcess_otherModel_returnsFalse() {
        InboundChannelModel model = new InboundChannelModel();
        assertThat(processor.canProcess(model)).isFalse();
    }

    @Test
    void registerInbound_validFields_subscribes() {
        NatsInboundChannelModel model = new NatsInboundChannelModel();
        model.setKey("testChannel");
        model.setSubject("order.new");
        model.setQueueGroup("order-service");

        processor.registerChannelModel(model, null, eventRegistry, eventRepositoryService, false);

        assertThat(model.getInboundEventChannelAdapter()).isNotNull();
    }

    @Test
    void registerInbound_noQueueGroup_subscribesWithout() {
        NatsInboundChannelModel model = new NatsInboundChannelModel();
        model.setKey("testChannel");
        model.setSubject("order.new");

        processor.registerChannelModel(model, null, eventRegistry, eventRepositoryService, false);

        assertThat(model.getInboundEventChannelAdapter()).isNotNull();
    }

    @Test
    void registerOutbound_validFields_createsAdapter() {
        NatsOutboundChannelModel model = new NatsOutboundChannelModel();
        model.setKey("testChannel");
        model.setSubject("order.completed");

        processor.registerChannelModel(model, null, eventRegistry, eventRepositoryService, false);

        assertThat(model.getOutboundEventChannelAdapter()).isNotNull();
    }

    @Test
    void registerInbound_missingSubject_throwsException() {
        NatsInboundChannelModel model = new NatsInboundChannelModel();
        model.setKey("testChannel");
        // subject is null

        assertThatThrownBy(() ->
                processor.registerChannelModel(model, null, eventRegistry, eventRepositoryService, false))
                .isInstanceOf(FlowableException.class)
                .hasMessageContaining("subject");
    }

    @Test
    void registerInbound_jetstreamTrue_throwsException() {
        NatsInboundChannelModel model = new NatsInboundChannelModel();
        model.setKey("testChannel");
        model.setSubject("order.new");
        model.setJetstream(true);

        assertThatThrownBy(() ->
                processor.registerChannelModel(model, null, eventRegistry, eventRepositoryService, false))
                .isInstanceOf(FlowableException.class)
                .hasMessageContaining("JetStream")
                .hasMessageContaining("not yet supported");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl . -Dtest=NatsChannelDefinitionProcessorTest -q`
Expected: FAIL — `NatsChannelDefinitionProcessor` class does not exist

- [ ] **Step 3: Write minimal implementation**

```java
package org.flowable.eventregistry.spring.nats;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.nats.client.Connection;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.eventregistry.api.ChannelModelProcessor;
import org.flowable.eventregistry.api.EventRegistry;
import org.flowable.eventregistry.api.EventRepositoryService;
import org.flowable.eventregistry.model.ChannelModel;
import org.flowable.eventregistry.spring.nats.channel.NatsInboundChannelModel;
import org.flowable.eventregistry.spring.nats.channel.NatsOutboundChannelModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NatsChannelDefinitionProcessor implements ChannelModelProcessor {

    private static final Logger log = LoggerFactory.getLogger(NatsChannelDefinitionProcessor.class);

    private final Connection connection;
    private final Map<String, NatsInboundEventChannelAdapter> inboundAdapters = new ConcurrentHashMap<>();

    public NatsChannelDefinitionProcessor(Connection connection) {
        this.connection = connection;
    }

    @Override
    public boolean canProcess(ChannelModel channelModel) {
        return channelModel instanceof NatsInboundChannelModel
                || channelModel instanceof NatsOutboundChannelModel;
    }

    @Override
    public boolean canProcessIfChannelModelAlreadyRegistered(ChannelModel channelModel) {
        return channelModel instanceof NatsOutboundChannelModel;
    }

    @Override
    public void registerChannelModel(ChannelModel channelModel, String tenantId,
            EventRegistry eventRegistry, EventRepositoryService eventRepositoryService,
            boolean fallbackToDefaultTenant) {

        if (channelModel instanceof NatsInboundChannelModel inboundModel) {
            registerInbound(inboundModel, tenantId, eventRegistry);
        } else if (channelModel instanceof NatsOutboundChannelModel outboundModel) {
            registerOutbound(outboundModel);
        }
    }

    @Override
    public void unregisterChannelModel(ChannelModel channelModel, String tenantId,
            EventRepositoryService eventRepositoryService) {

        String key = resolveKey(channelModel, tenantId);
        NatsInboundEventChannelAdapter adapter = inboundAdapters.remove(key);
        if (adapter != null) {
            adapter.unsubscribe();
        }
    }

    private void registerInbound(NatsInboundChannelModel model, String tenantId,
            EventRegistry eventRegistry) {

        validateSubject(model.getSubject(), model.getKey());
        validateJetstream(model.isJetstream(), model.getKey());

        NatsInboundEventChannelAdapter adapter = new NatsInboundEventChannelAdapter(
                connection, model.getSubject(), model.getQueueGroup());

        model.setInboundEventChannelAdapter(adapter);
        adapter.setInboundChannelModel(model);
        adapter.setEventRegistry(eventRegistry);
        adapter.subscribe();

        inboundAdapters.put(resolveKey(model, tenantId), adapter);
    }

    private void registerOutbound(NatsOutboundChannelModel model) {
        validateSubject(model.getSubject(), model.getKey());
        validateJetstream(model.isJetstream(), model.getKey());

        NatsOutboundEventChannelAdapter adapter = new NatsOutboundEventChannelAdapter(
                connection, model.getSubject());
        model.setOutboundEventChannelAdapter(adapter);
    }

    private void validateSubject(String subject, String channelKey) {
        if (subject == null || subject.isBlank()) {
            throw new FlowableException(
                    "NATS channel '" + channelKey + "': subject is required");
        }
    }

    private void validateJetstream(boolean jetstream, String channelKey) {
        if (jetstream) {
            throw new FlowableException(
                    "NATS channel '" + channelKey + "': JetStream is not yet supported (planned for Phase 2)");
        }
    }

    private String resolveKey(ChannelModel channelModel, String tenantId) {
        if (tenantId != null) {
            return tenantId + "#" + channelModel.getKey();
        }
        return channelModel.getKey();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl . -Dtest=NatsChannelDefinitionProcessorTest -q`
Expected: 8 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/flowable/eventregistry/spring/nats/NatsChannelDefinitionProcessor.java \
        src/test/java/org/flowable/eventregistry/spring/nats/NatsChannelDefinitionProcessorTest.java
git commit -m "feat: add NatsChannelDefinitionProcessor with TDD"
```

---

### Task 7: NatsProperties

**Files:**
- Create: `src/main/java/org/flowable/eventregistry/spring/nats/config/NatsProperties.java`

- [ ] **Step 1: Create NatsProperties**

```java
package org.flowable.eventregistry.spring.nats.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.nats")
public class NatsProperties {

    private String url = "nats://localhost:4222";
    private String username;
    private String password;
    private String token;
    private String credentialsFile;
    private Duration connectionTimeout = Duration.ofSeconds(5);
    private int maxReconnects = -1;
    private Duration reconnectWait = Duration.ofSeconds(2);
    private final Tls tls = new Tls();

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getCredentialsFile() {
        return credentialsFile;
    }

    public void setCredentialsFile(String credentialsFile) {
        this.credentialsFile = credentialsFile;
    }

    public Duration getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(Duration connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public int getMaxReconnects() {
        return maxReconnects;
    }

    public void setMaxReconnects(int maxReconnects) {
        this.maxReconnects = maxReconnects;
    }

    public Duration getReconnectWait() {
        return reconnectWait;
    }

    public void setReconnectWait(Duration reconnectWait) {
        this.reconnectWait = reconnectWait;
    }

    public Tls getTls() {
        return tls;
    }

    public static class Tls {

        private boolean enabled = false;
        private String certFile;
        private String keyFile;
        private String caFile;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCertFile() {
            return certFile;
        }

        public void setCertFile(String certFile) {
            this.certFile = certFile;
        }

        public String getKeyFile() {
            return keyFile;
        }

        public void setKeyFile(String keyFile) {
            this.keyFile = keyFile;
        }

        public String getCaFile() {
            return caFile;
        }

        public void setCaFile(String caFile) {
            this.caFile = caFile;
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/flowable/eventregistry/spring/nats/config/NatsProperties.java
git commit -m "feat: add NatsProperties for spring.nats.* configuration binding"
```

---

### Task 8: NatsChannelAutoConfiguration (test-first)

**Files:**
- Create: `src/test/java/org/flowable/eventregistry/spring/nats/config/NatsChannelAutoConfigurationTest.java`
- Create: `src/main/java/org/flowable/eventregistry/spring/nats/config/NatsChannelAutoConfiguration.java`

- [ ] **Step 1: Write failing tests**

```java
package org.flowable.eventregistry.spring.nats.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.nats.client.Connection;
import org.flowable.eventregistry.api.EventRegistryEngine;
import org.flowable.eventregistry.spring.nats.NatsChannelDefinitionProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class NatsChannelAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NatsChannelAutoConfiguration.class));

    @Test
    void autoConfig_withAllDependencies_createsBeans() {
        // This test validates bean definitions exist, not actual connection
        // Connection bean creation will fail without a running NATS server,
        // but the auto-configuration class should be loadable
        contextRunner
                .withPropertyValues("spring.nats.url=nats://localhost:4222")
                .run(context -> {
                    assertThat(context).hasSingleBean(NatsProperties.class);
                });
    }

    @Test
    void autoConfig_customConnectionBean_usesCustom() {
        contextRunner
                .withUserConfiguration(CustomConnectionConfig.class)
                .run(context -> {
                    // Custom connection bean should prevent auto-config from creating one
                    assertThat(context).hasSingleBean(Connection.class);
                    assertThat(context.getBean(Connection.class))
                            .isSameAs(CustomConnectionConfig.MOCK_CONNECTION);
                });
    }

    @Test
    void autoConfig_missingFlowable_doesNotLoad() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(NatsChannelAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(EventRegistryEngine.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(NatsChannelDefinitionProcessor.class);
                });
    }

    @Configuration
    static class CustomConnectionConfig {
        static final Connection MOCK_CONNECTION = org.mockito.Mockito.mock(Connection.class);

        @Bean
        Connection natsConnection() {
            return MOCK_CONNECTION;
        }

        @Bean
        NatsChannelDefinitionProcessor natsChannelDefinitionProcessor() {
            return new NatsChannelDefinitionProcessor(MOCK_CONNECTION);
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl . -Dtest=NatsChannelAutoConfigurationTest -q`
Expected: FAIL — `NatsChannelAutoConfiguration` class does not exist

- [ ] **Step 3: Write implementation**

```java
package org.flowable.eventregistry.spring.nats.config;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import io.nats.client.Connection;
import io.nats.client.ErrorListener;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.flowable.eventregistry.api.EventRegistryEngine;
import org.flowable.eventregistry.spring.nats.NatsChannelDefinitionProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass({ Connection.class, EventRegistryEngine.class })
@EnableConfigurationProperties(NatsProperties.class)
public class NatsChannelAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(NatsChannelAutoConfiguration.class);

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public Connection natsConnection(NatsProperties props) throws IOException, InterruptedException {
        Options.Builder builder = new Options.Builder()
                .server(props.getUrl())
                .connectionTimeout(props.getConnectionTimeout())
                .maxReconnects(props.getMaxReconnects())
                .reconnectWait(props.getReconnectWait())
                .connectionListener((conn, event) -> {
                    switch (event) {
                        case CONNECTED -> log.info("NATS connected: {}", conn.getServerInfo().getHost());
                        case RECONNECTED -> log.warn("NATS reconnected: {}", conn.getServerInfo().getHost());
                        case DISCONNECTED -> log.warn("NATS disconnected");
                        case CLOSED -> log.info("NATS connection closed");
                        default -> log.debug("NATS connection event: {}", event);
                    }
                })
                .errorListener(new ErrorListener() {
                    @Override
                    public void errorOccurred(Connection conn, String error) {
                        log.error("NATS error: {}", error);
                    }

                    @Override
                    public void exceptionOccurred(Connection conn, Exception exp) {
                        log.error("NATS exception", exp);
                    }

                    @Override
                    public void slowConsumerDetected(Connection conn, io.nats.client.Consumer consumer) {
                        log.warn("NATS slow consumer on subject: {}", consumer.getSubject());
                    }
                });

        configureAuth(builder, props);

        return Nats.connect(builder.build());
    }

    @Bean
    @ConditionalOnMissingBean
    public NatsChannelDefinitionProcessor natsChannelDefinitionProcessor(Connection connection) {
        return new NatsChannelDefinitionProcessor(connection);
    }

    private void configureAuth(Options.Builder builder, NatsProperties props) {
        if (props.getCredentialsFile() != null) {
            builder.authHandler(Nats.credentials(props.getCredentialsFile()));
        } else if (props.getToken() != null) {
            builder.token(props.getToken().toCharArray());
        } else if (props.getUsername() != null) {
            builder.userInfo(props.getUsername(), props.getPassword());
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl . -Dtest=NatsChannelAutoConfigurationTest -q`
Expected: 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/flowable/eventregistry/spring/nats/config/NatsChannelAutoConfiguration.java \
        src/test/java/org/flowable/eventregistry/spring/nats/config/NatsChannelAutoConfigurationTest.java
git commit -m "feat: add NatsChannelAutoConfiguration with Spring Boot auto-config"
```

---

### Task 9: Integration Test — Inbound (NATS -> Flowable)

**Files:**
- Create: `src/test/java/org/flowable/eventregistry/spring/nats/NatsInboundChannelIntegrationTest.java`

**Prerequisite:** Docker must be running for Testcontainers.

- [ ] **Step 1: Write integration test**

```java
package org.flowable.eventregistry.spring.nats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.flowable.eventregistry.api.EventRegistry;
import org.flowable.eventregistry.api.InboundEvent;
import org.flowable.eventregistry.model.InboundChannelModel;
import org.flowable.eventregistry.spring.nats.channel.NatsInboundChannelModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class NatsInboundChannelIntegrationTest {

    @Container
    static GenericContainer<?> natsContainer = new GenericContainer<>("nats:2.10-alpine")
            .withExposedPorts(4222);

    private Connection connection;
    private Connection publisherConnection;
    private final List<InboundEvent> receivedEvents = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        String natsUrl = "nats://" + natsContainer.getHost() + ":" + natsContainer.getMappedPort(4222);
        connection = Nats.connect(new Options.Builder().server(natsUrl).build());
        publisherConnection = Nats.connect(new Options.Builder().server(natsUrl).build());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
        if (publisherConnection != null) publisherConnection.close();
    }

    @Test
    void inboundChannel_receivesAndProcessesEvent() {
        NatsInboundChannelModel model = new NatsInboundChannelModel();
        model.setKey("testInbound");
        model.setSubject("test.inbound");

        EventRegistry eventRegistry = createCapturingEventRegistry();

        NatsInboundEventChannelAdapter adapter = new NatsInboundEventChannelAdapter(
                connection, "test.inbound", null);
        adapter.setInboundChannelModel(model);
        adapter.setEventRegistry(eventRegistry);
        adapter.subscribe();

        publisherConnection.publish("test.inbound",
                "{\"orderId\":42}".getBytes(StandardCharsets.UTF_8));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(receivedEvents).hasSize(1);
            assertThat(receivedEvents.get(0).getBody()).isEqualTo("{\"orderId\":42}");
        });

        adapter.unsubscribe();
    }

    @Test
    void inboundChannel_withQueueGroup_loadBalances() throws Exception {
        NatsInboundChannelModel model1 = new NatsInboundChannelModel();
        model1.setKey("testInbound1");
        model1.setSubject("test.queue");

        NatsInboundChannelModel model2 = new NatsInboundChannelModel();
        model2.setKey("testInbound2");
        model2.setSubject("test.queue");

        List<InboundEvent> events1 = new CopyOnWriteArrayList<>();
        List<InboundEvent> events2 = new CopyOnWriteArrayList<>();

        NatsInboundEventChannelAdapter adapter1 = new NatsInboundEventChannelAdapter(
                connection, "test.queue", "test-group");
        adapter1.setInboundChannelModel(model1);
        adapter1.setEventRegistry(createCapturingEventRegistry(events1));
        adapter1.subscribe();

        String natsUrl = "nats://" + natsContainer.getHost() + ":" + natsContainer.getMappedPort(4222);
        Connection conn2 = Nats.connect(new Options.Builder().server(natsUrl).build());

        NatsInboundEventChannelAdapter adapter2 = new NatsInboundEventChannelAdapter(
                conn2, "test.queue", "test-group");
        adapter2.setInboundChannelModel(model2);
        adapter2.setEventRegistry(createCapturingEventRegistry(events2));
        adapter2.subscribe();

        // Publish single message
        publisherConnection.publish("test.queue",
                "{\"msg\":\"single\"}".getBytes(StandardCharsets.UTF_8));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            int totalReceived = events1.size() + events2.size();
            assertThat(totalReceived).isEqualTo(1);
        });

        adapter1.unsubscribe();
        adapter2.unsubscribe();
        conn2.close();
    }

    private EventRegistry createCapturingEventRegistry() {
        return createCapturingEventRegistry(receivedEvents);
    }

    private EventRegistry createCapturingEventRegistry(List<InboundEvent> targetList) {
        // Minimal EventRegistry implementation that captures events
        return new EventRegistryStub(targetList);
    }
}
```

Note: `EventRegistryStub` is a minimal test helper. Create it as a package-private class in the same test directory. **Important:** The `EventRegistry` interface may have additional methods in Flowable 7.1.0 beyond what's shown here. Check the actual interface at compile time and add no-op stubs for any missing methods:

```java
package org.flowable.eventregistry.spring.nats;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.flowable.eventregistry.api.*;
import org.flowable.eventregistry.api.runtime.EventInstance;
import org.flowable.eventregistry.model.ChannelModel;
import org.flowable.eventregistry.model.InboundChannelModel;

class EventRegistryStub implements EventRegistry {

    private final List<InboundEvent> capturedEvents;

    EventRegistryStub(List<InboundEvent> capturedEvents) {
        this.capturedEvents = capturedEvents;
    }

    @Override
    public void eventReceived(InboundChannelModel channelModel, InboundEvent event) {
        capturedEvents.add(event);
    }

    // --- Unused methods — no-op ---
    @Override public void setInboundEventProcessor(InboundEventProcessor p) {}
    @Override public void setOutboundEventProcessor(OutboundEventProcessor p) {}
    @Override public OutboundEventProcessor getSystemOutboundEventProcessor() { return null; }
    @Override public void setSystemOutboundEventProcessor(OutboundEventProcessor p) {}
    @Override public void registerEventRegistryEventConsumer(EventRegistryEventConsumer c) {}
    @Override public void removeFlowableEventRegistryEventConsumer(EventRegistryEventConsumer c) {}
    @Override public String generateKey(Map<String, Object> data) { return null; }
    @Override public void eventReceived(InboundChannelModel m, String event) {}
    @Override public void sendEventToConsumers(EventRegistryEvent e) {}
    @Override public void sendSystemEventOutbound(EventInstance e) {}
    @Override public void sendEventOutbound(EventInstance e, Collection<ChannelModel> c) {}
}
```

- [ ] **Step 2: Run integration tests**

Run: `mvn test -pl . -Dtest=NatsInboundChannelIntegrationTest -q`
Expected: 2 tests PASS (requires Docker running)

- [ ] **Step 3: Commit**

```bash
git add src/test/java/org/flowable/eventregistry/spring/nats/NatsInboundChannelIntegrationTest.java \
        src/test/java/org/flowable/eventregistry/spring/nats/EventRegistryStub.java
git commit -m "test: add NATS inbound channel integration tests with Testcontainers"
```

---

### Task 10: Integration Test — Outbound (Flowable -> NATS)

**Files:**
- Create: `src/test/java/org/flowable/eventregistry/spring/nats/NatsOutboundChannelIntegrationTest.java`

- [ ] **Step 1: Write integration test**

```java
package org.flowable.eventregistry.spring.nats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class NatsOutboundChannelIntegrationTest {

    @Container
    static GenericContainer<?> natsContainer = new GenericContainer<>("nats:2.10-alpine")
            .withExposedPorts(4222);

    private Connection connection;
    private Connection subscriberConnection;

    @BeforeEach
    void setUp() throws Exception {
        String natsUrl = "nats://" + natsContainer.getHost() + ":" + natsContainer.getMappedPort(4222);
        connection = Nats.connect(new Options.Builder().server(natsUrl).build());
        subscriberConnection = Nats.connect(new Options.Builder().server(natsUrl).build());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
        if (subscriberConnection != null) subscriberConnection.close();
    }

    @Test
    void outboundChannel_publishesEvent() throws Exception {
        List<Message> receivedMessages = new CopyOnWriteArrayList<>();

        // Subscribe first to capture outbound messages
        subscriberConnection.createDispatcher()
                .subscribe("test.outbound", receivedMessages::add);

        // Flush to ensure subscription is propagated to server
        subscriberConnection.flush(Duration.ofSeconds(2));

        NatsOutboundEventChannelAdapter adapter = new NatsOutboundEventChannelAdapter(
                connection, "test.outbound");
        adapter.sendEvent("{\"status\":\"completed\"}", Collections.emptyMap());
        connection.flush(Duration.ofSeconds(2));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(receivedMessages).hasSize(1);
            String body = new String(receivedMessages.get(0).getData(), StandardCharsets.UTF_8);
            assertThat(body).isEqualTo("{\"status\":\"completed\"}");
        });
    }
}
```

- [ ] **Step 2: Run integration test**

Run: `mvn test -pl . -Dtest=NatsOutboundChannelIntegrationTest -q`
Expected: 1 test PASS

- [ ] **Step 3: Commit**

```bash
git add src/test/java/org/flowable/eventregistry/spring/nats/NatsOutboundChannelIntegrationTest.java
git commit -m "test: add NATS outbound channel integration test with Testcontainers"
```

---

### Task 11: Run Full Test Suite + Final Verification

**Files:** None (verification only)

- [ ] **Step 1: Run all tests**

Run: `mvn test -q`
Expected: 21 tests PASS, BUILD SUCCESS

- [ ] **Step 2: Verify test counts**

Run: `mvn test 2>&1 | grep -E "Tests run:|BUILD"`
Expected output similar to:
```
Tests run: 8  (NatsChannelDefinitionProcessorTest)
Tests run: 5  (NatsInboundEventChannelAdapterTest)
Tests run: 2  (NatsOutboundEventChannelAdapterTest)
Tests run: 3  (NatsChannelAutoConfigurationTest)
Tests run: 2  (NatsInboundChannelIntegrationTest)
Tests run: 1  (NatsOutboundChannelIntegrationTest)
BUILD SUCCESS
```

- [ ] **Step 3: Verify file structure matches spec**

Run: `find src -name "*.java" | sort`
Expected:
```
src/main/java/org/flowable/eventregistry/spring/nats/NatsChannelDefinitionProcessor.java
src/main/java/org/flowable/eventregistry/spring/nats/NatsInboundEvent.java
src/main/java/org/flowable/eventregistry/spring/nats/NatsInboundEventChannelAdapter.java
src/main/java/org/flowable/eventregistry/spring/nats/NatsOutboundEventChannelAdapter.java
src/main/java/org/flowable/eventregistry/spring/nats/channel/NatsInboundChannelModel.java
src/main/java/org/flowable/eventregistry/spring/nats/channel/NatsOutboundChannelModel.java
src/main/java/org/flowable/eventregistry/spring/nats/config/NatsChannelAutoConfiguration.java
src/main/java/org/flowable/eventregistry/spring/nats/config/NatsProperties.java
src/test/java/org/flowable/eventregistry/spring/nats/EventRegistryStub.java
src/test/java/org/flowable/eventregistry/spring/nats/NatsChannelDefinitionProcessorTest.java
src/test/java/org/flowable/eventregistry/spring/nats/NatsInboundChannelIntegrationTest.java
src/test/java/org/flowable/eventregistry/spring/nats/NatsInboundEventChannelAdapterTest.java
src/test/java/org/flowable/eventregistry/spring/nats/NatsOutboundChannelIntegrationTest.java
src/test/java/org/flowable/eventregistry/spring/nats/NatsOutboundEventChannelAdapterTest.java
src/test/java/org/flowable/eventregistry/spring/nats/config/NatsChannelAutoConfigurationTest.java
```

- [ ] **Step 4: Commit (if any adjustments were needed)**

```bash
git add -A
git commit -m "chore: final verification — all 20 tests passing"
```
