# flowable-nats-channel — Vizyon & Yol Haritası

**Tarih:** 23 Mart 2026
**Sahip:** 3eAI Labs Ltd (UK) — Levent Sezgin Genç
**Lisans:** Apache License 2.0
**Durum:** Tasarım aşaması

---

## 1. Vizyon

**"Flowable + NATS.io = Enterprise-grade workflow orchestration, sıfır lisans maliyeti."**

flowable-nats-channel, Flowable Event Registry için NATS.io/JetStream channel adapter'ıdır. Flowable'ın mevcut Kafka, RabbitMQ ve JMS adapter'larının yanına NATS desteği ekleyerek, yüksek performanslı ve düşük maliyetli bir workflow altyapısı sunar.

Bu proje, Camunda 8'in lisanslama değişikliği (v8.6+, Ekim 2024) sonrası oluşan pazar boşluğuna yanıt veren açık kaynak bir alternatifin temel bileşenidir.

---

## 2. Problem

### 2.1 Camunda 8 Lisans Sorunu
- Camunda 8.6'dan itibaren Zeebe dahil tüm bileşenler production'da paralı
- Yıllık enterprise lisans maliyeti $50,000+ USD
- Camunda 7 End of Life'a girdi — güvenlik yamaları bitti (Ekim 2025)
- Mevcut Camunda 7 kullanıcıları geçiş yapmak zorunda

### 2.2 Flowable'ın Eksik Parçası
- Flowable açık kaynak, production'da ücretsiz (Apache 2.0)
- BPMN, CMMN, DMN tam desteği var
- Ama Event Registry'de sadece Kafka, RabbitMQ, JMS desteği var
- NATS.io desteği yok — hızlı, hafif, cloud-native messaging backend eksik
- Zeebe'nin yüksek throughput avantajına karşılık Flowable tek başına RDBMS darboğazına takılabilir

### 2.3 Çözüm: Flowable + NATS.io
NATS JetStream'i Flowable'ın Event Registry'sine entegre ederek:
- Yüksek hacimli iş dağıtımı (Zeebe'nin external task pattern'ı yerine)
- Push-based delivery (Zeebe'nin long-polling'inden daha düşük latency)
- Partitioning ve load balancing (NATS queue groups ile)
- Persistence ve exactly-once delivery (JetStream ile)

---

## 3. Pazar Fırsatı

### 3.1 Hedef Kitle
- **Camunda 7 kullanıcıları** — Migration zorunluluğu olan, $50K+ lisans ödemeye isteksiz şirketler
- **Telekom operatörleri** — On-premise tercih eden, yüksek hacimli workflow ihtiyacı olan (OTA, SIM management, provisioning)
- **Flowable community** — NATS.io kullanmak isteyen ancak adapter bulamayan geliştiriciler
- **Cloud-native projeler** — CNCF ekosisteminde (Kubernetes, NATS) çalışan organizasyonlar

### 3.2 Rekabet Avantajı

| Özellik | Camunda 8 Enterprise | Flowable + NATS |
|---------|---------------------|-----------------|
| Workflow engine lisansı | $50K+/yıl | $0 (Apache 2.0) |
| Messaging lisansı | Dahil (Zeebe) | $0 (NATS, Apache 2.0) |
| BPMN 2.0 | Tam | Tam |
| CMMN (Case Management) | Yok | Tam |
| DMN (Decision) | Tam | Tam |
| High throughput | Zeebe partitioning | NATS JetStream |
| Deployment esnekliği | Kubernetes zorunlu | Embedded / standalone / K8s |
| On-premise | Karmaşık | Kolay |
| Push-based delivery | Hayır (long-polling) | Evet (NATS native) |
| Toplam yıllık maliyet | $50K+ | $0 |

### 3.3 İş Modeli (3eAI Labs)
Bu proje açık kaynak olarak yayınlanacak. Gelir modeli:
- **Danışmanlık:** Camunda → Flowable migration hizmeti
- **Entegrasyon:** flowable-nats-channel kurulum ve yapılandırma
- **Destek:** Enterprise destek sözleşmeleri
- **OTA ürünü:** cn-advanced-ota projesinde doğrudan kullanım

---

## 4. Teknik Mimari

### 4.1 Flowable Event Registry Mimarisi

Flowable'ın channel/event ayrımı bu projenin temelidir:

```
┌─────────────────────────────────────────────────────┐
│                 BPMN / CMMN Model                    │
│  (Event'i bilir, transport'u bilmez)                 │
└───────────────────────┬─────────────────────────────┘
                        │ Event Reference
┌───────────────────────▼─────────────────────────────┐
│              Flowable Event Registry                  │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────┐ │
│  │ Event Model │  │ Channel Model│  │ Serializer │ │
│  │ (data)      │  │ (transport)  │  │ (JSON/XML) │ │
│  └─────────────┘  └──────┬───────┘  └────────────┘ │
└──────────────────────────┼──────────────────────────┘
                           │
          ┌────────────────┼────────────────┐
          │                │                │
   ┌──────▼──────┐ ┌──────▼──────┐ ┌──────▼──────┐
   │    Kafka    │ │  RabbitMQ  │ │   NATS.io   │ ← YENİ
   │   Adapter   │ │   Adapter   │ │   Adapter   │
   └─────────────┘ └─────────────┘ └─────────────┘
```

### 4.2 NATS Channel Adapter Bileşenleri

Flowable'ın Kafka adapter'ı 9+ Java sınıfından oluşuyor. NATS versiyonu aynı pattern'ı izleyecek:

```
flowable-nats-channel/
├── src/main/java/org/flowable/eventregistry/spring/nats/
│   ├── NatsChannelDefinitionProcessor.java       → Channel tanımını NATS'e bağlar
│   ├── NatsInboundChannelAdapter.java            → NATS subscribe → Flowable event
│   ├── NatsOutboundChannelAdapter.java           → Flowable event → NATS publish
│   ├── NatsMessageInboundEvent.java              → NATS mesajını event'e wrap eder
│   ├── NatsSubjectProvider.java                  → Subject routing stratejisi
│   ├── NatsQueueGroupProvider.java               → Queue group ile load balancing
│   ├── NatsJetStreamConfiguration.java           → JetStream persistence ayarları
│   └── NatsChannelAutoConfiguration.java         → Spring Boot auto-config
│
├── src/test/java/org/flowable/eventregistry/spring/nats/
│   ├── NatsChannelDefinitionProcessorTest.java
│   ├── NatsInboundChannelAdapterTest.java
│   ├── NatsOutboundChannelAdapterTest.java
│   ├── NatsJetStreamIntegrationTest.java
│   └── NatsChannelE2ETest.java                   → BPMN + NATS end-to-end
│
├── pom.xml                                        → Maven (Flowable ekosistemi Maven)
├── README.md
├── LICENSE                                         → Apache 2.0
└── docs/
    └── 01-vision-roadmap.md                        → Bu doküman
```

### 4.3 NATS.io Özellikleri ve Kullanım

| NATS Özelliği | Kullanım | Zeebe Karşılığı |
|---------------|----------|-----------------|
| Core NATS (pub/sub) | Hafif event iletimi, fire-and-forget | — |
| JetStream (persistent) | Guaranteed delivery, replay | Zeebe event log |
| Queue Groups | Worker load balancing | Zeebe job activation |
| Subject hierarchy | Topic routing (ota.jobs.*, campaign.*) | Zeebe job type |
| Key-Value Store | Lightweight state cache | — |
| Request-Reply | Sync workflow calls | — |

### 4.4 Hibrit Mimari: Flowable + NATS (OTA Örneği)

```
┌──────────────────────────────────────────────────────┐
│                    Admin UI / API                      │
└───────────────────────┬──────────────────────────────┘
                        │ REST / WebSocket
┌───────────────────────▼──────────────────────────────┐
│              Flowable Engine (BPMN/CMMN)              │
│  - Process orchestration                              │
│  - State management (PostgreSQL)                      │
│  - Decision engine (DMN)                              │
│  - Human tasks                                        │
└───────┬───────────────────────────────┬──────────────┘
        │ Outbound Event                │ Inbound Event
┌───────▼───────────────────────────────▼──────────────┐
│              NATS JetStream                            │
│  ┌─────────────┐  ┌─────────────┐  ┌──────────────┐ │
│  │ ota.jobs.*  │  │ campaign.*  │  │ provision.*  │ │
│  │ (high vol.) │  │ (scheduled) │  │ (lifecycle)  │ │
│  └──────┬──────┘  └──────┬──────┘  └──────┬───────┘ │
└─────────┼────────────────┼─────────────────┼─────────┘
          │                │                 │
   ┌──────▼──────┐ ┌──────▼──────┐ ┌───────▼───────┐
   │ OTA Worker  │ │  Campaign   │ │  Provisioning │
   │ (Go)        │ │  Worker(Go) │ │  Worker (Go)  │
   └─────────────┘ └─────────────┘ └───────────────┘
```

**Flowable** → ağır iş: process orchestration, state, karar noktaları, human task
**NATS** → hafif iş: yüksek hacimli mesaj dağıtımı, worker coordination, event streaming
**PostgreSQL** → sadece state (hafif yük, darboğaz değil)

---

## 5. Teknoloji Stack'i

| Bileşen | Teknoloji | Lisans | Versiyon |
|---------|-----------|--------|----------|
| Workflow Engine | Flowable | Apache 2.0 | 7.x |
| Messaging | NATS.io + JetStream | Apache 2.0 | 2.10+ |
| NATS Java Client | io.nats:jnats | Apache 2.0 | 2.19+ |
| Framework | Spring Boot | Apache 2.0 | 3.x |
| Build | Maven | Apache 2.0 | 3.9+ |
| Database | PostgreSQL | PostgreSQL License | 16+ |
| Cache | Redis | BSD 3-Clause | 7+ |
| Test | JUnit 5 + Testcontainers | — | — |
| Java | JDK | — | 17+ |

**Toplam lisans maliyeti: $0**

---

## 6. Yol Haritası

### Faz 1 — Core Adapter (2 hafta)
**Hedef:** Temel inbound/outbound NATS channel adapter

| # | İş Kalemi | Süre | Öncelik |
|---|-----------|------|---------|
| 1.1 | Flowable Kafka adapter kaynak kod analizi | 1 gün | P0 |
| 1.2 | NatsChannelDefinitionProcessor | 2 gün | P0 |
| 1.3 | NatsInboundChannelAdapter (subscribe → event) | 1.5 gün | P0 |
| 1.4 | NatsOutboundChannelAdapter (event → publish) | 1.5 gün | P0 |
| 1.5 | NatsChannelAutoConfiguration (Spring Boot) | 1 gün | P0 |
| 1.6 | Unit testler | 1.5 gün | P0 |
| 1.7 | Basit BPMN + NATS entegrasyon testi | 1.5 gün | P0 |

**Çıktı:** Core NATS pub/sub ile çalışan Flowable channel adapter

### Faz 2 — JetStream & Advanced Features (2 hafta)
**Hedef:** Persistence, guaranteed delivery, queue groups

| # | İş Kalemi | Süre | Öncelik |
|---|-----------|------|---------|
| 2.1 | JetStream persistence desteği | 2 gün | P0 |
| 2.2 | Queue group ile load balancing | 1 gün | P0 |
| 2.3 | Subject hierarchy routing | 1 gün | P1 |
| 2.4 | Retry / dead letter handling | 1.5 gün | P0 |
| 2.5 | Exactly-once delivery semantics | 1.5 gün | P1 |
| 2.6 | Entegrasyon testleri (Testcontainers + NATS) | 1.5 gün | P0 |
| 2.7 | Performans benchmark (vs Kafka adapter) | 1.5 gün | P1 |

**Çıktı:** Production-ready JetStream desteği

### Faz 3 — External Task Pattern (1 hafta)
**Hedef:** Zeebe-benzeri worker pattern NATS üzerinden

| # | İş Kalemi | Süre | Öncelik |
|---|-----------|------|---------|
| 3.1 | NatsExternalTaskWorker base class | 2 gün | P0 |
| 3.2 | Task completion / failure reporting | 1 gün | P0 |
| 3.3 | Worker timeout ve retry mekanizması | 1 gün | P0 |
| 3.4 | Go worker SDK (cn-advanced-ota için) | 1 gün | P1 |

**Çıktı:** Worker pattern — Flowable task'ı NATS'e publish, worker consume edip complete

### Faz 4 — Dokümantasyon & Yayın (1 hafta)
**Hedef:** GitHub'da açık kaynak yayın

| # | İş Kalemi | Süre | Öncelik |
|---|-----------|------|---------|
| 4.1 | README.md (kurulum, yapılandırma, örnekler) | 1 gün | P0 |
| 4.2 | Örnek BPMN projeleri (3 senaryo) | 1 gün | P0 |
| 4.3 | Maven Central'a publish | 0.5 gün | P0 |
| 4.4 | GitHub Actions CI/CD | 0.5 gün | P0 |
| 4.5 | Flowable forum/blog duyurusu | 0.5 gün | P1 |
| 4.6 | Benchmark raporu yayını | 0.5 gün | P1 |

**Çıktı:** Açık kaynak proje, Maven Central'da erişilebilir

---

## 7. OTA Projesi Migration Planı

flowable-nats-channel tamamlandıktan sonra cn-advanced-ota projesindeki Camunda 7 → Flowable migration:

| Adım | İş | Etki |
|------|-----|------|
| 1 | Camunda Go client → Flowable REST API client | `internal/core/workflow/client.go` değişir |
| 2 | BPMN process tanımlarını Flowable formatına uyarla | Minimal fark (Camunda 7 ≈ Flowable) |
| 3 | External task pattern → NATS worker pattern | `internal/worker/job_worker.go` güncellenir |
| 4 | Camunda Cockpit → custom Flowable monitoring | Yeni dashboard gerekli |
| 5 | Docker compose güncelle (Camunda → Flowable + NATS) | `docker-compose.yml` değişir |
| 6 | E2E test (Bulk SIM Import, Campaign, Provisioning) | Mevcut senaryolar korunur |

**Tahmini süre:** 2 hafta (flowable-nats-channel hazır olduktan sonra)

---

## 8. Riskler ve Azaltma

| # | Risk | Etki | Olasılık | Azaltma |
|---|------|------|----------|---------|
| R1 | Flowable Event Registry API değişikliği | Yüksek | Düşük | Flowable LTS versiyonuna bağlan, API uyumluluk testleri |
| R2 | NATS JetStream exactly-once semantics zorlukları | Orta | Orta | At-least-once ile başla, idempotent worker pattern |
| R3 | Performansın Zeebe'yi yakalayamaması | Orta | Düşük | Benchmark ile kanıtla, NATS zaten çok hızlı |
| R4 | Flowable community ilgisizliği | Düşük | Orta | Kaliteli dokümantasyon, gerçek kullanım senaryosu (OTA) |
| R5 | NATS Java client (jnats) Spring Boot uyumsuzluğu | Orta | Düşük | Testcontainers ile kapsamlı entegrasyon testi |
| R6 | Go worker SDK eksikliği | Orta | Orta | Faz 3'te basit SDK, sonra genişlet |

---

## 9. Başarı Metrikleri

| Metrik | 3 Ay | 6 Ay | 12 Ay |
|--------|------|------|-------|
| GitHub Stars | 50 | 200 | 500 |
| Maven Central indirme / ay | 100 | 500 | 2000 |
| Flowable forum aktif thread | 5 | 15 | 30 |
| Production kullanan proje | 1 (OTA) | 3 | 10 |
| Danışmanlık geliri (migration) | — | $5,000 | $20,000 |

---

## 10. Referanslar

### Flowable
- [Flowable Event Registry - Channels & Events](https://documentation.flowable.com/latest/howto/howto/howto-getting-started-channel-events)
- [Flowable Kafka Adapter Kaynak Kodu](https://github.com/flowable/flowable-engine/tree/main/modules/flowable-event-registry-spring/src/main/java/org/flowable/eventregistry/spring/kafka)
- [Flowable Custom Inbound Channel](https://documentation.flowable.com/latest/howto/howto/howto-custom-inbound-channel)
- [Flowable Open Source](https://www.flowable.com/open-source)

### NATS.io
- [NATS.io Resmi Site](https://nats.io)
- [NATS JetStream](https://docs.nats.io/nats-concepts/jetstream)
- [NATS Java Client (jnats)](https://github.com/nats-io/nats.java)
- [NATS Go Client](https://github.com/nats-io/nats.go)

### Camunda Lisanslama
- [Camunda 8 Licensing Update (Nisan 2024)](https://camunda.com/blog/2024/04/licensing-update-camunda-8-self-managed/)
- [How Open is Camunda Platform 8?](https://camunda.com/blog/2022/05/how-open-is-camunda-platform-8/)
- [Camunda Pricing](https://camunda.com/pricing/)
