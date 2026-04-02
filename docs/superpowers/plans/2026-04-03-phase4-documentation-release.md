# Phase 4: Documentation & Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prepare the flowable-nats-channel project for open source release with README, CI/CD, and Maven Central publishing.

**Architecture:** No code changes to production classes. Create README.md, two GitHub Actions workflows, RELEASING.md guide, and update pom.xml with Maven Central metadata + release profile.

**Tech Stack:** GitHub Actions, Maven Central (Sonatype OSSRH), GPG signing, Maven plugins (source, javadoc, gpg, nexus-staging)

**Spec:** `docs/superpowers/specs/phase4-documentation-release.md`

---

## File Structure

### New Files
```
README.md                                    # Project documentation
RELEASING.md                                 # Maintainer release guide
.github/workflows/ci.yml                    # Build + test on push/PR
.github/workflows/release.yml               # Maven Central publish on tag
```

### Modified Files
```
pom.xml                                      # SCM, developers, distribution, release profile
```

---

### Task 1: pom.xml Maven Central Metadata + Release Profile

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add SCM, developers, and distribution management**

After the `</licenses>` closing tag (line 21), add:

```xml
<scm>
    <connection>scm:git:git://github.com/3eAI-Labs/flowable-nats-channel.git</connection>
    <developerConnection>scm:git:ssh://github.com:3eAI-Labs/flowable-nats-channel.git</developerConnection>
    <url>https://github.com/3eAI-Labs/flowable-nats-channel</url>
</scm>

<developers>
    <developer>
        <name>Levent Sezgin Genc</name>
        <organization>3eAI Labs Ltd</organization>
        <organizationUrl>https://3eai.com</organizationUrl>
    </developer>
</developers>

<distributionManagement>
    <snapshotRepository>
        <id>ossrh</id>
        <url>https://s01.oss.sonatype.org/content/repositories/snapshots</url>
    </snapshotRepository>
    <repository>
        <id>ossrh</id>
        <url>https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/</url>
    </repository>
</distributionManagement>
```

- [ ] **Step 2: Add release profile**

After the `</build>` closing tag (line 144), add:

```xml
<profiles>
    <profile>
        <id>release</id>
        <build>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-source-plugin</artifactId>
                    <version>3.3.1</version>
                    <executions>
                        <execution>
                            <id>attach-sources</id>
                            <goals><goal>jar-no-fork</goal></goals>
                        </execution>
                    </executions>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-javadoc-plugin</artifactId>
                    <version>3.10.1</version>
                    <executions>
                        <execution>
                            <id>attach-javadocs</id>
                            <goals><goal>jar</goal></goals>
                        </execution>
                    </executions>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-gpg-plugin</artifactId>
                    <version>3.2.7</version>
                    <executions>
                        <execution>
                            <id>sign-artifacts</id>
                            <phase>verify</phase>
                            <goals><goal>sign</goal></goals>
                        </execution>
                    </executions>
                </plugin>
                <plugin>
                    <groupId>org.sonatype.plugins</groupId>
                    <artifactId>nexus-staging-maven-plugin</artifactId>
                    <version>1.7.0</version>
                    <extensions>true</extensions>
                    <configuration>
                        <serverId>ossrh</serverId>
                        <nexusUrl>https://s01.oss.sonatype.org/</nexusUrl>
                        <autoReleaseAfterClose>true</autoReleaseAfterClose>
                    </configuration>
                </plugin>
            </plugins>
        </build>
    </profile>
</profiles>
```

- [ ] **Step 3: Verify compilation still works**

Run: `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "chore: add Maven Central metadata and release profile to pom.xml"
```

---

### Task 2: GitHub Actions CI Workflow

**Files:**
- Create: `.github/workflows/ci.yml`

- [ ] **Step 1: Create directory**

```bash
mkdir -p .github/workflows
```

- [ ] **Step 2: Create CI workflow**

Create `.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'maven'

      - name: Build and Test
        run: mvn verify --batch-mode --no-transfer-progress
```

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: add GitHub Actions CI workflow for build and test"
```

---

### Task 3: GitHub Actions Release Workflow

**Files:**
- Create: `.github/workflows/release.yml`

- [ ] **Step 1: Create release workflow**

Create `.github/workflows/release.yml`:

```yaml
name: Release

on:
  push:
    tags: ['v*']

jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'maven'
          server-id: ossrh
          server-username: MAVEN_USERNAME
          server-password: MAVEN_PASSWORD
          gpg-private-key: ${{ secrets.GPG_PRIVATE_KEY }}
          gpg-passphrase: GPG_PASSPHRASE

      - name: Publish to Maven Central
        run: mvn deploy --batch-mode --no-transfer-progress -P release -DskipTests
        env:
          MAVEN_USERNAME: ${{ secrets.OSSRH_USERNAME }}
          MAVEN_PASSWORD: ${{ secrets.OSSRH_TOKEN }}
          GPG_PASSPHRASE: ${{ secrets.GPG_PASSPHRASE }}
```

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/release.yml
git commit -m "ci: add GitHub Actions release workflow for Maven Central publishing"
```

---

### Task 4: RELEASING.md

**Files:**
- Create: `RELEASING.md`

- [ ] **Step 1: Create releasing guide**

Create `RELEASING.md`:

```markdown
# Releasing to Maven Central

## One-Time Setup

### 1. Sonatype OSSRH Account

1. Create an account at https://issues.sonatype.org/
2. Open a JIRA ticket requesting access to `com.3eai` group ID
3. Sonatype will ask you to verify domain ownership — add a DNS TXT record to `3eai.com`
4. Wait for approval (usually 1-2 business days)

### 2. GPG Key

```bash
# Generate key (RSA 4096, no expiry recommended for CI)
gpg --full-generate-key

# List keys to find your KEY_ID
gpg --list-keys

# Upload public key to keyserver
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>

# Export private key for GitHub Actions
gpg --armor --export-secret-keys <KEY_ID> > private-key.asc
```

### 3. GitHub Repository Secrets

Go to **Settings → Secrets and variables → Actions** and add:

| Secret | Value |
|--------|-------|
| `OSSRH_USERNAME` | Your Sonatype OSSRH username |
| `OSSRH_TOKEN` | Your Sonatype OSSRH token |
| `GPG_PRIVATE_KEY` | Contents of `private-key.asc` |
| `GPG_PASSPHRASE` | Your GPG key passphrase |

Delete `private-key.asc` after adding the secret.

## Release Process

### 1. Prepare the release

```bash
# Update version (remove -SNAPSHOT)
mvn versions:set -DnewVersion=0.1.0
git commit -am "release: v0.1.0"
```

### 2. Tag and push

```bash
git tag v0.1.0
git push origin main --tags
```

This triggers the Release workflow which:
- Builds the project
- Signs artifacts with GPG
- Deploys to Sonatype OSSRH staging repository
- Auto-releases to Maven Central

### 3. Set next development version

```bash
mvn versions:set -DnewVersion=0.2.0-SNAPSHOT
git commit -am "chore: set next development version 0.2.0-SNAPSHOT"
git push origin main
```

### 4. Create GitHub Release (optional)

```bash
gh release create v0.1.0 --title "v0.1.0" --notes "Initial release"
```

## Verify

After ~30 minutes, check Maven Central:
https://central.sonatype.com/artifact/com.3eai/flowable-nats-channel
```

- [ ] **Step 2: Commit**

```bash
git add RELEASING.md
git commit -m "docs: add RELEASING.md maintainer guide for Maven Central publishing"
```

---

### Task 5: README.md

**Files:**
- Create: `README.md`

- [ ] **Step 1: Create README**

Create `README.md`:

````markdown
# flowable-nats-channel

[![CI](https://github.com/3eAI-Labs/flowable-nats-channel/actions/workflows/ci.yml/badge.svg)](https://github.com/3eAI-Labs/flowable-nats-channel/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

NATS.io channel adapter for [Flowable](https://www.flowable.com/open-source) Event Registry. Enables Flowable BPMN/CMMN processes to send and receive events via NATS Core, JetStream, and Request-Reply.

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

## Requirements

- Java 21+
- Spring Boot 3.x
- Flowable 7.x
- NATS 2.10+ (for JetStream `nakWithDelay`)
- `spring.threads.virtual.enabled: true` (recommended for optimal performance)

## Roadmap

| Phase | Status |
|-------|--------|
| Core NATS pub/sub | :white_check_mark: Complete |
| JetStream (persistent, DLQ, backoff) | :white_check_mark: Complete |
| Request-Reply (external workers) | :white_check_mark: Complete |
| Documentation & Release | :white_check_mark: Complete |
| Camunda 7.x compatibility study | :crystal_ball: Planned |
| Key-Value Store integration | :crystal_ball: Planned |
| Object Store integration | :crystal_ball: Planned |

## Contributing

Contributions are welcome! Please open an issue first to discuss what you would like to change.

## License

[Apache License 2.0](LICENSE)

Copyright 2026 [3eAI Labs Ltd](https://3eai.com)
````

- [ ] **Step 2: Verify README renders correctly**

Run: `head -5 README.md` to confirm file exists and starts correctly.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: add comprehensive README with quickstart, config reference, and examples"
```

---

### Task 6: Final Verification + Push

**Files:** None (verification only)

- [ ] **Step 1: Verify all tests still pass**

Run: `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 mvn test`
Expected: 55 tests PASS, BUILD SUCCESS

- [ ] **Step 2: Verify file structure**

Run: `ls README.md RELEASING.md LICENSE .github/workflows/ci.yml .github/workflows/release.yml`
Expected: all 5 files exist

- [ ] **Step 3: Push to remote**

```bash
git push origin main
```
