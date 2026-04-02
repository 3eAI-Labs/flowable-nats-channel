# Phase 4: Documentation & Release

## Overview

Prepare the flowable-nats-channel project for open source release: comprehensive README, GitHub Actions CI/CD, and Maven Central publishing.

**Scope:** README.md, GitHub Actions workflows (CI + Release), Maven Central pom.xml metadata + release profile, RELEASING.md guide.

**Prerequisites:**
- Phase 1-3 complete (14 production classes, 55 tests passing)
- GitHub repository: `3eAI-Labs/flowable-nats-channel`
- Domain `3eai.com` owned (for Sonatype group ID verification)

**License:** Apache 2.0

---

## 1. README.md

### 1.1 Structure

```
# flowable-nats-channel

[Badges: Build, Maven Central, License]

## Why this project?
## Who is this for?
## Features
## Quick Start
## Configuration Reference
## Channel Definitions
## Request-Reply (External Workers)
## Roadmap
## Contributing
## License
```

### 1.2 Why This Project?

Camunda 8 (v8.6+, October 2024) moved all components including Zeebe to a paid license — $50K+/year for enterprise. Camunda 7 has reached End of Life with no more security patches.

Flowable is an open source (Apache 2.0) BPMN/CMMN/DMN engine with full Camunda 7 compatibility. This project adds NATS.io messaging support to Flowable's Event Registry, providing a high-performance, zero-cost alternative to the Camunda 8 + Zeebe stack.

### 1.3 Who Is This For?

Three target audiences:

1. **Camunda 7 users facing EOL** — Migrate to Flowable (same BPMN 2.0 standard) with NATS messaging instead of paying $50K+/year for Camunda 8.

2. **Camunda 7 fork maintainers** — Some organizations maintain forks of Camunda 7 (e.g., 7.4) with their own security patches and features. This project offers a modern messaging layer that can inform your architecture, or a full migration path to Flowable when ready.

3. **Flowable users wanting NATS** — Add high-performance NATS messaging to your existing Flowable setup. Core NATS, JetStream, and Request-Reply patterns out of the box.

### 1.4 Comparison Table

```markdown
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
| Total annual cost | $50K+ | $0 |
```

### 1.5 Features List

- **Core NATS** — Pub/sub inbound and outbound event channels
- **JetStream** — Persistent messaging with ack/nack, exponential backoff (nakWithDelay), dead letter queue (JetStream primary + Core NATS fallback)
- **Request-Reply** — BPMN service task delegates work to external workers via NATS, workers respond with results
- **Virtual Threads** — Java 21 virtual thread offloading for non-blocking I/O
- **Micrometer Metrics** — Counters for consume/ack/nak/dlq/publish/request-reply + processing Timer
- **Structured Logging** — SLF4J `kv()` format with MDC trace_id propagation
- **Spring Boot Auto-Configuration** — Zero-config setup with `spring.nats.*` properties
- **Auth** — Username/password, token, credentials file, NKey

### 1.6 Quick Start

```xml
<dependency>
    <groupId>com.3eai</groupId>
    <artifactId>flowable-nats-channel</artifactId>
    <version>0.1.0</version>
</dependency>
```

```yaml
spring:
  nats:
    url: nats://localhost:4222
```

Inbound channel JSON example + outbound channel JSON example + request-reply BPMN XML example.

### 1.7 Configuration Reference

Full `spring.nats.*` property table:

| Property | Default | Description |
|----------|---------|-------------|
| `url` | `nats://localhost:4222` | NATS server URL |
| `username` | — | Username auth |
| `password` | — | Password auth |
| `token` | — | Token auth |
| `credentials-file` | — | Credentials file path |
| `nkey-file` | — | NKey seed file |
| `connection-timeout` | `5s` | Connection timeout |
| `max-reconnects` | `-1` (infinite) | Max reconnection attempts |
| `reconnect-wait` | `2s` | Wait between reconnects |
| `tls.enabled` | `false` | Enable TLS |
| `tls.cert-file` | — | Client certificate |
| `tls.key-file` | — | Client private key |
| `tls.ca-file` | — | CA certificate |

### 1.8 Channel Definition Examples

Core NATS inbound, Core NATS outbound, JetStream inbound (with DLQ), JetStream outbound — JSON examples from Phase 1-2 specs.

### 1.9 Request-Reply Section

BPMN service task XML example + worker examples in Go, Java, Python — from Phase 3 spec.

### 1.10 Roadmap

```markdown
| Phase | Status |
|-------|--------|
| Core NATS pub/sub | ✅ Complete |
| JetStream (persistent, DLQ, backoff) | ✅ Complete |
| Request-Reply (external workers) | ✅ Complete |
| Documentation & Release | ✅ Complete |
| Camunda 7.x compatibility study | 🔮 Planned |
| Key-Value Store integration | 🔮 Planned |
| Object Store integration | 🔮 Planned |
```

---

## 2. GitHub Actions CI/CD

### 2.1 CI Workflow (`.github/workflows/ci.yml`)

Triggers: push to main, pull requests.

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
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'maven'
      - run: mvn verify --batch-mode --no-transfer-progress
```

Testcontainers uses Docker which is available on `ubuntu-latest` by default.

### 2.2 Release Workflow (`.github/workflows/release.yml`)

Triggers: tag push matching `v*`.

```yaml
name: Release
on:
  push:
    tags: ['v*']
jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'maven'
          server-id: ossrh
          server-username: MAVEN_USERNAME
          server-password: MAVEN_PASSWORD
          gpg-private-key: ${{ secrets.GPG_PRIVATE_KEY }}
          gpg-passphrase: GPG_PASSPHRASE
      - run: mvn deploy --batch-mode -P release -DskipTests
        env:
          MAVEN_USERNAME: ${{ secrets.OSSRH_USERNAME }}
          MAVEN_PASSWORD: ${{ secrets.OSSRH_TOKEN }}
          GPG_PASSPHRASE: ${{ secrets.GPG_PASSPHRASE }}
```

### 2.3 Required GitHub Secrets

| Secret | Purpose |
|--------|---------|
| `OSSRH_USERNAME` | Sonatype OSSRH username |
| `OSSRH_TOKEN` | Sonatype OSSRH token |
| `GPG_PRIVATE_KEY` | GPG signing key (armored export) |
| `GPG_PASSPHRASE` | GPG key passphrase |

---

## 3. Maven Central Setup

### 3.1 pom.xml Additions

Required metadata for Maven Central compliance:

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

### 3.2 Release Profile

Activated by `mvn deploy -P release`. Includes:

- `maven-source-plugin` — attach source JAR
- `maven-javadoc-plugin` — attach javadoc JAR
- `maven-gpg-plugin` — GPG sign all artifacts
- `nexus-staging-maven-plugin` — deploy to Sonatype OSSRH with auto-release

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

---

## 4. RELEASING.md

Guide for project maintainers documenting the one-time setup and release process.

### 4.1 One-Time Setup

1. Create Sonatype OSSRH account at https://issues.sonatype.org/
2. Open JIRA ticket for `com.3eai` group ID — add DNS TXT record for domain verification
3. Generate GPG key: `gpg --full-generate-key` (RSA 4096, no expiry)
4. Upload GPG public key to keyserver: `gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>`
5. Export private key: `gpg --armor --export-secret-keys <KEY_ID>`
6. Add GitHub repository secrets: OSSRH_USERNAME, OSSRH_TOKEN, GPG_PRIVATE_KEY, GPG_PASSPHRASE

### 4.2 Release Process

```bash
# 1. Update version in pom.xml (remove -SNAPSHOT)
mvn versions:set -DnewVersion=0.1.0

# 2. Commit and tag
git commit -am "release: v0.1.0"
git tag v0.1.0

# 3. Push tag — triggers release workflow
git push origin main --tags

# 4. Set next development version
mvn versions:set -DnewVersion=0.2.0-SNAPSHOT
git commit -am "chore: set next development version 0.2.0-SNAPSHOT"
git push origin main
```

---

## 5. File Summary

| File | Type | Description |
|------|------|-------------|
| `README.md` | Create | Project documentation — why, quickstart, config, examples |
| `.github/workflows/ci.yml` | Create | Build + test on push/PR |
| `.github/workflows/release.yml` | Create | Maven Central publish on tag |
| `RELEASING.md` | Create | Maintainer guide for releases |
| `pom.xml` | Modify | Add SCM, developers, distribution, release profile |

No test changes — Phase 4 is documentation and CI/CD only.

---

## Appendix: Key Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| 1 | README targets 3 audiences | Camunda 7 EOL users, Camunda 7 fork maintainers, Flowable users |
| 2 | Maven Central via Sonatype OSSRH | Standard for open source Java. GitHub Packages requires extra repo config. |
| 3 | Tag-triggered release | `git tag v0.1.0 && git push` → automatic Maven Central publish |
| 4 | Separate CI and Release workflows | CI runs on every push/PR, Release only on tags |
| 5 | `com.3eai` group ID | Domain-verified, professional branding |
| 6 | Examples in README (not separate repo) | Lower barrier to entry. Separate example projects can come later. |
