# OpenTelemetry sans GCP — Spring Boot standard, avec ou sans microservices

> Question F5 : *« Peut-on utiliser OTel sans GCP, sans microservice, sur une app Spring Boot
> plus standard ? Spring Boot /actuator/prometheus → OTel Collector (prometheus receiver) →
> visualiser autre que GCP ? »*
>
> **Réponse courte : OUI, complètement.** GCP n'est qu'**un backend parmi d'autres**. OTel est
> vendor-neutral par conception : on change l'**exporter**, pas le code de l'application.

---

## 1. Le principe : OTel découple l'app du backend

```
[ App instrumentée ]→ (OTLP / scrape) → [ Collector (optionnel) ] → (exporter) → [ Backend ]
                                                                                    │
                        Le SEUL élément spécifique GCP est l'EXPORTER ──────────────┘
```

Sur kube-train, `k8s/observability/otel-collector.yaml` utilise l'exporter `googlecloud`.
Pour sortir de GCP, on remplace juste cet exporter :

| Backend | Exporter Collector | Ce qu'on visualise |
|---------|--------------------|--------------------|
| **GCP** (actuel) | `googlecloud` | Cloud Trace + Cloud Monitoring |
| **Jaeger** | `otlp` → `jaeger:4317` | traces |
| **Grafana Tempo** | `otlp` → tempo | traces |
| **Prometheus** | `prometheus` / `prometheusremotewrite` | métriques |
| **Grafana LGTM / SigNoz / Elastic APM** | `otlp` | traces + métriques + logs |

👉 **Démo concrète, 100 % locale** : `docker-compose.observability.yml` +
`extra/otel-local-stack-runbook.md` (Démo B). Aucune ligne de code applicatif changée.

---

## 2. La question précise : /actuator/prometheus → OTel Collector → visualiser

Oui, c'est exactement le montage de la Démo B :

```
Spring Boot (Micrometer → /actuator/prometheus)
   → OTel Collector [ prometheus receiver ]   (scrape l'endpoint)
   → OTel Collector [ prometheus exporter :8889 ]
   → Prometheus (serveur)                      (scrape :8889)
   → Grafana                                    (visualise)
```

Fichier : `observability/otel-collector-config.yaml`. Le `prometheus receiver` scrape le **même**
endpoint `/actuator/prometheus` qu'en prod — c'est juste un scraper Prometheus intégré au Collector.

### As-tu BESOIN du Collector ici ? Non — c'est optionnel

Pour une app Spring Boot standard, 3 niveaux de complexité croissante :

| Niveau | Montage | Quand |
|--------|---------|-------|
| **1. Le plus simple** | Prometheus scrape **directement** `/actuator/prometheus` + Grafana. Pas d'OTel du tout. | Mono-app, métriques seules |
| **2. + traces** | Agent OTel (ou Micrometer Tracing) → OTLP → Jaeger/Tempo **directement**. Pas de Collector. | Traces intra-app ou 2-3 services |
| **3. + Collector** | App → Collector → backends. Le Collector centralise, filtre, batch, découple. | Plusieurs apps, plusieurs backends, prod |

Le **Collector** est un *middleware* : utile pour le fan-out (traces→Jaeger, métriques→Prometheus,
logs→Loki), le traitement (filtrage, attributs, sampling) et le découplage app↔backend. En dev
mono-service, le scrape direct Prometheus suffit.

---

## 3. Sans microservices ? Oui

- **Métriques** : `micrometer-registry-prometheus` (déjà dans kube-train) expose `/actuator/prometheus`.
  → Prometheus + Grafana. C'est la stack Spring Boot **classique**, zéro OTel, zéro GCP.
- **Traces** : même sur **une seule** app, le tracing est utile — une trace montre la chaîne interne
  `HTTP → service → repository/DB → client HTTP sortant`. Le tracing *distribué* (propagation entre
  services) n'apporte un plus qu'à partir de 2 services, mais l'instrumentation intra-app fonctionne seule.
- **Logs** : logs structurés (ECS/JSON) → Loki/Elastic, corrélés au `traceId` (MDC).

---

## 4. Comment instrumenter une app Spring Boot standard (3 options)

| Option | Comment | Avantage | Sur kube-train |
|--------|---------|----------|----------------|
| **Agent OTel** (`-javaagent`) | Zéro code, auto-instrumente HTTP/JDBC/Kafka… | Rien à coder | ✅ **utilisé** |
| **OTel SDK** | Dépendances `opentelemetry-sdk` + spans manuels | Contrôle fin | partiel (propagation Pub/Sub manuelle) |
| **Micrometer + Micrometer Tracing** | Natif Spring Boot ; `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` | Intégré à l'écosystème Spring, pas d'agent | alternative possible |

> Pour une app **standard** sans agent : `micrometer-registry-prometheus` (métriques) +
> `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` (traces), `application.properties` :
> ```properties
> management.otlp.tracing.endpoint=http://localhost:4317
> management.tracing.sampling.probability=1.0
> ```
> → traces vers Jaeger/Tempo, métriques vers Prometheus. **Aucun GCP.**

---

## 5. À retenir

1. OTel est **agnostique du backend** : GCP = un exporter, remplaçable.
2. `/actuator/prometheus → Collector (prometheus receiver) → Prometheus/Grafana` : **oui**, montré en Démo B.
3. Le **Collector est optionnel** : en mono-app, Prometheus scrape direct suffit.
4. Sans microservices, métriques + tracing intra-app restent pertinents.
5. Backends non-GCP prêts à l'emploi : **Prometheus+Grafana** (métriques), **Jaeger/Tempo** (traces),
   ou un all-in-one (**Grafana LGTM, SigNoz, Elastic**).

*Voir aussi : `extra/otel-local-stack-runbook.md` (démos exécutables) et `k8s/observability/otel-collector.yaml` (exporter `googlecloud` à substituer).*
