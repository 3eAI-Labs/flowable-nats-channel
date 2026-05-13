# Repo Rename Checklist — flowable-nats-channel → nats-bpm-channels

**Tarih:** 13 Mayıs 2026
**Sebep:** Üç engine (Flowable, Camunda 7, CadenzaFlow) peer-level konumlandırılırken repo adının nötr olması.

> Bu adımlar `lsg` tarafından yürütülecek — push/rename paylaşılan state'i etkilediği için Claude bunları kendisi çalıştırmaz.

## 1. GitHub repo rename

```bash
# Settings → Repository name üzerinden veya gh CLI ile:
gh repo rename nats-bpm-channels --repo 3eAI-Labs/flowable-nats-channel
```

GitHub otomatik bir HTTP 301 redirect oluşturur (`flowable-nats-channel` → `nats-bpm-channels`), eski URL'lerden gelen istekler çalışmaya devam eder. Yine de aşağıdaki dosyalardaki link/URL'leri elle güncellemek gerekir.

## 2. Local git remote güncelle

```bash
cd ~/Workspaces/3eai-labs/flowable-nats-channel
git remote set-url origin git@github.com:3eAI-Labs/nats-bpm-channels.git
git remote -v   # doğrula
```

> Dizin adı `flowable-nats-channel/` lokalde kalabilir veya istenirse `mv ~/Workspaces/3eai-labs/flowable-nats-channel ~/Workspaces/3eai-labs/nats-bpm-channels`. Memory'deki tmux session mapping etkilenir mi — kontrol edilmeli.

## 3. README ve doc URL'leri

Bu refactor'ünde aşağıdaki dosyalar yeni repo adına güncellendi:
- `pom.xml` — `<url>`, `<scm>` blok'u
- `README.md` — CI badge URL'i

Repo rename ile uyumlu — başka bir değişiklik gerekmez.

## 4. GitHub Actions / CI

`.github/workflows/ci.yml` rename'den etkilenmez. Badge URL'i README'de zaten yeni repo adına bağlı.

## 5. Maven Central artifact

**Etkilenmez.** Maven artifact koordinatları (`com.3eai:flowable-nats-channel`, `com.3eai:camunda-nats-channel`, `com.3eai:cadenzaflow-nats-channel`) repo adından bağımsız — `groupId` ve `artifactId` aynı kalır.

İlk Maven Central yayını (`0.1.0`) henüz yapılmamışsa, sonraki release `nats-bpm-channels` repo adı altında olur ama artifact koordinatları değişmez.

## 6. External referanslar

Blog post / forum thread / Twitter / LinkedIn paylaşımları varsa eski URL'ler redirect ile çalışır ama elle güncellenmesi daha sağlıklı:
- 3eai.com web sitesi (varsa link)
- Flowable forum thread'ler
- CadenzaFlow docs içinde referans

## 7. CadenzaFlow modülü ön-doğrulama

Yeni `cadenzaflow-nats-channel` modülü `org.cadenzaflow.bpm:cadenzaflow-engine:1.2.0` artifact'ine bağımlı. Bu artifact:

- **Lokal install:** `cd ~/Workspaces/cadenzaflow/cadenzaflow-bpm-platform && JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-amd64 mvn install -DskipTests` (cadenzaflow Java JDK kuralı, memory `feedback_cadenzaflow_java_home`)
- **Maven Central:** Yayınlanmadıysa local install zorunlu

İlk build denemesi:
```bash
cd ~/Workspaces/3eai-labs/flowable-nats-channel
mvn clean compile -pl cadenzaflow-nats-channel -am
```

`-am` flag'i `nats-core` bağımlılığını da build eder. Compile hatası alırsanız önce parent + nats-core install:
```bash
mvn clean install -pl nats-core -am
```

## 8. Geri alma

GitHub rename geri alınabilir (`gh repo rename flowable-nats-channel ...`). Local git remote, badge URL'leri, pom <url> da geri yüklenir.

`cadenzaflow-nats-channel/` modülü silinmek istenirse:
1. `pom.xml`'den `<module>cadenzaflow-nats-channel</module>` satırını çıkar
2. `pom.xml`'den `<cadenzaflow.version>` property ve `cadenzaflow-engine` dependencyManagement entry'sini çıkar
3. `rm -rf cadenzaflow-nats-channel/`
4. README'den CadenzaFlow bölümünü çıkar
5. Vision doc'tan CadenzaFlow referanslarını çıkar
