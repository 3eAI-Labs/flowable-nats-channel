# nats-bpm-channels — Vizyon & Yol Haritası

**Tarih:** 13 Mayıs 2026
**Sahip:** 3eAI Labs Ltd (UK) — Levent Sezgin Genç
**Lisans:** Apache License 2.0
**Durum:** Üç adapter yayında (Flowable, Camunda 7, CadenzaFlow)

---

## 1. Vizyon

**"BPM engine'leriniz için sıfır-lisans, yüksek-performans NATS.io messaging."**

nats-bpm-channels, üç açık kaynak BPM engine için ortak bir NATS.io messaging katmanı sunar. Aynı mesajlaşma altyapısı üzerine üç engine bağlayıcısı oturur; siz stack'inize uyanını seçersiniz.

Camunda 8'in lisans değişikliği (v8.6+, Ekim 2024) ve Camunda 7'nin EOL'ü (Ekim 2025) sonrası oluşan boşlukta, üç engine'i de Zeebe muadili bir özellik setiyle eşit konumda tutmak hedef.

---

## 2. Problem

### 2.1 Camunda 8 lisans değişikliği
- v8.6'dan itibaren Zeebe dahil tüm bileşenler production'da paralı
- Enterprise lisans $50K+ USD/yıl
- Camunda 7 EOL — güvenlik yamaları durdu (Ekim 2025)

### 2.2 Açık kaynak BPM dünyasında messaging boşluğu
- **Flowable** Apache 2.0, BPMN/CMMN/DMN tam destekli, ama Event Registry'de NATS adapter yok (sadece Kafka/RabbitMQ/JMS)
- **Camunda 7** mevcut kullanıcı tabanı büyük; EOL sonrası community fork'lar (CadenzaFlow dahil) sürdürülüyor, fakat modern messaging katmanı olmadan kalıyor
- **CadenzaFlow** Camunda 7 fork'u; rebrand + güvenlik bakımı sürüyor, NATS desteği bu projeden geliyor

### 2.3 Çözüm: tek messaging stack, üç engine binding
NATS.io + JetStream üzerinde üç engine için ortak özellik seti:
- Core NATS pub/sub
- JetStream persistent messaging (ack/nack, exponential backoff, DLQ)
- Request-Reply ile external worker pattern (Zeebe job worker muadili)

---

## 3. Hedef kitle

- **Camunda 7 kullanıcıları** — EOL sonrası, $50K+ lisans ödemek istemeyenler
- **CadenzaFlow kullanıcıları** — Camunda 7 lineage'ında kalmak isteyen, rebrand'i kabul edenler
- **Flowable kullanıcıları** — NATS messaging istiyor ama hazır adapter bulamayanlar
- **Telekom / on-premise senaryolar** — yüksek hacim, düşük latency, hafif operasyon (NATS tek binary)
- **Cloud-native projeler** — CNCF ekosisteminde NATS kullananlar

---

## 4. Rekabet konumu

| Özellik | Camunda 8 Enterprise | Bu proje (üç engine) |
|---------|---------------------|----------------------|
| Workflow engine lisansı | $50K+/yıl | $0 (Apache 2.0) |
| Messaging lisansı | Dahil (Zeebe) | $0 (NATS, Apache 2.0) |
| BPMN 2.0 | Tam | Tam (üç engine de) |
| CMMN | Yok | Flowable (tam) |
| DMN | Tam | Flowable + Camunda 7 + CadenzaFlow |
| High throughput | Zeebe partitioning | NATS JetStream |
| Deployment esnekliği | K8s zorunlu | Embedded / standalone / K8s |
| On-premise | Karmaşık | Kolay |
| Push-based delivery | Hayır (long-poll) | Evet (NATS native) |
| Toplam yıllık maliyet | $50K+ | $0 |

---

## 5. Teknik mimari

### 5.1 Üç bağlayıcı, tek messaging core

```
                ┌───────────────────────┐
                │      nats-core         │
                │  (Connection, Auth,   │
                │   JetStream mgmt,     │
                │   Metrics, Headers)   │
                └──────────┬────────────┘
                           │
        ┌──────────────────┼──────────────────┐
        │                  │                  │
┌───────▼────────┐ ┌───────▼────────┐ ┌───────▼────────┐
│   flowable-    │ │    camunda-    │ │  cadenzaflow-  │
│  nats-channel  │ │  nats-channel  │ │  nats-channel  │
│                │ │                │ │                │
│ Event Registry │ │ JavaDelegate   │ │ JavaDelegate   │
│ ChannelAdapter │ │ + Message      │ │ + Message      │
│                │ │   Correlation  │ │   Correlation  │
└────────────────┘ └────────────────┘ └────────────────┘
```

### 5.2 Üç pattern, üç bağlayıcıda da aynı

| Pattern | Outbound (engine → NATS) | Inbound (NATS → engine) |
|---|---|---|
| **Core NATS pub/sub** | `NatsPublishDelegate` (Camunda/CadenzaFlow) / `NatsOutboundChannelAdapter` (Flowable) | `NatsMessageCorrelationSubscriber` (Camunda/CadenzaFlow) / `NatsInboundChannelAdapter` (Flowable) |
| **JetStream durable** | `JetStreamPublishDelegate` | `JetStreamMessageCorrelationSubscriber` (ack/nack/DLQ) |
| **Request-Reply** | `NatsRequestReplyDelegate` (engine → worker → engine) | — |

### 5.3 Hibrit mimari örneği (OTA senaryosu)

```
┌────────────────────────────────────┐
│         Admin UI / API              │
└────────────────┬───────────────────┘
                 │ REST / WebSocket
┌────────────────▼───────────────────┐
│  BPM Engine (Flowable/Camunda 7/   │
│              CadenzaFlow)           │
│  - Process orchestration            │
│  - State (PostgreSQL)               │
│  - DMN, human tasks                 │
└────┬─────────────────────┬─────────┘
     │ Outbound Event       │ Inbound Event
┌────▼─────────────────────▼─────────┐
│        NATS JetStream                │
│  ota.jobs.*  campaign.*  provision.* │
└────┬──────────┬─────────────┬───────┘
     │          │             │
┌────▼───┐ ┌────▼────┐ ┌──────▼──────┐
│ OTA    │ │Campaign │ │Provisioning │
│Worker  │ │ Worker  │ │  Worker     │
└────────┘ └─────────┘ └─────────────┘
```

Engine ağır işi yapar (orchestration, state, karar noktaları); NATS hafif işi (yüksek hacim mesaj dağıtımı, worker koordinasyonu).

---

## 6. Teknoloji stack'i

| Bileşen | Teknoloji | Lisans | Versiyon |
|---------|-----------|--------|----------|
| Engine (1/3) | Flowable | Apache 2.0 | 7.1+ |
| Engine (2/3) | Camunda 7 | Apache 2.0 | 7.24+ |
| Engine (3/3) | CadenzaFlow | Apache 2.0 | 1.2+ |
| Messaging | NATS.io + JetStream | Apache 2.0 (CNCF Graduated) | 2.10+ |
| NATS Java client | io.nats:jnats | Apache 2.0 | 2.20+ |
| Framework | Spring Boot | Apache 2.0 | 3.3+ |
| Build | Maven | Apache 2.0 | 3.9+ |
| Java | JDK | — | 21+ |
| Test | JUnit 5 + Testcontainers + Mockito + AssertJ | — | — |

**Toplam lisans maliyeti: $0**

---

## 7. Modül durumu

| Modül | Durum |
|---|---|
| `nats-core` | Yayında |
| `flowable-nats-channel` | Yayında |
| `camunda-nats-channel` | Yayında |
| `cadenzaflow-nats-channel` | Yayında |

### Sıradaki yetenekler (sırasız, scope açık)
- NATS Key-Value Store entegrasyonu (process variable cache, distributed lock)
- NATS Object Store entegrasyonu (attachment/binary payload offload)
- Reference worker SDK'ları (Go, Java, Python) — request-reply pattern için template
- Performance benchmark yayını (Kafka adapter ve Zeebe karşılaştırması)

---

## 8. Riskler

| # | Risk | Azaltma |
|---|------|---------|
| R1 | Flowable Event Registry API değişimi | LTS sürümüne pin, integration test |
| R2 | Camunda 7 lineage'ında upstream güvenlik yaması yok | CadenzaFlow fork'unun sürdürülmesi (3eAI Labs) |
| R3 | JetStream exactly-once semantik kenar durumları | At-least-once + idempotent worker pattern (dokümante) |
| R4 | NATS müşteri tarafında Kafka kadar tanıdık değil | L2 (iç bus) ile L3 (müşteri Kafka connector'ı) ayrımı netleştirilir |
| R5 | jnats Spring Boot uyumsuzluğu (transitive bağımlılık) | Testcontainers ile her sürümde kapsamlı integration test |

---

## 9. İş modeli (3eAI Labs)

Proje açık kaynak (Apache 2.0). Ticari gelir kapsamı:

- **Danışmanlık** — Camunda 8 → Flowable / CadenzaFlow geçişi
- **Entegrasyon** — özelleştirilmiş NATS kurulumu (TLS, NKey auth, JetStream tuning)
- **Enterprise destek** — SLA'lı destek sözleşmeleri
- **Doğrudan kullanım** — 3eAI Labs ürünlerinde (`cn-advanced-ota`, telekom orchestration senaryoları)

---

## 10. Referanslar

### Flowable
- [Event Registry — Channels & Events](https://documentation.flowable.com/latest/howto/howto/howto-getting-started-channel-events)
- [Flowable Open Source](https://www.flowable.com/open-source)

### Camunda 7
- [Camunda 7 Manual 7.24](https://docs.camunda.org/manual/7.24/)
- [Camunda 7 EOL Announcement](https://github.com/camunda/camunda-bpm-platform)

### CadenzaFlow
- [CadenzaFlow](https://cadenzaflow.com)

### NATS.io
- [NATS.io](https://nats.io)
- [JetStream](https://docs.nats.io/nats-concepts/jetstream)
- [jnats](https://github.com/nats-io/nats.java)

### Camunda 8 lisans
- [Licensing Update Apr 2024](https://camunda.com/blog/2024/04/licensing-update-camunda-8-self-managed/)
- [Camunda Pricing](https://camunda.com/pricing/)
