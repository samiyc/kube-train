# 🛡️ Formation 3 — DevOps, Sécurité & Qualité (5 jours)

> **Vision** : Cette formation capitalise sur les acquis des formations 1 (Kubernetes/Minikube) et 2
> (Cloud-Native : GCP, CI/CD, Kafka, Observability, Cloud SQL) pour monter en compétence sur les
> dimensions **DevOps avancé**, **sécurité applicative** et **qualité logicielle** — les trois piliers
> qui différencient un développeur senior d'un tech lead cloud-native.
>
> Chaque journée produit un **livrable concret** committable sur le projet `kube-train`.
> Les 4 sujets planifiés en Formation 2 mais non implémentés (Contract Testing, Outbox Pattern,
> OpenTelemetry, Flyway) sont intégrés ici avec les nouveaux sujets à fort ROI mission.

---

## 📐 Architecture cible (état final après J5)

```
┌──────────────────────────────────────────────────────────────────────┐
│                              GKE Autopilot Cluster                   │
│                                                                      │
│  ┌──────────────────────┐         ┌──────────────────────────┐       │
│  │  kube-train-api      │         │  train-notification-svc  │       │
│  │  (Spring Boot 4)     │         │  (Spring Boot 4)         │       │
│  │                      │         │                          │       │
│  │  • OAuth2 Resource   │  Kafka  │  • Kafka Consumer        │       │
│  │    Server            ├────────►│  • OpenTelemetry Agent   │       │
│  │  • Flyway migrations │         │  • Contract Stub         │       │
│  │  • Outbox table      │         │                          │       │
│  │  • OpenTelemetry     │         └──────────┬───────────────┘       │
│  │  • Cucumber BDD      │                    │                       │
│  │  • Contract Producer │                    │ traces                │
│  └──────────┬───────────┘                    ▼                       │
│             │                    ┌───────────────────────┐           │
│             │ SQL                │  Cloud Trace / Tempo  │           │
│             ▼                    └───────────────────────┘           │
│  ┌───────────────────────┐                                           │
│  │  Cloud SQL (Postgres) │◄── Flyway V1..Vn                          │
│  │  + outbox table       │                                           │
│  └───────────────────────┘                                           │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐     │
│  │  Network Policies (zero-trust)                              │     │
│  │  • api ↔ notification : port 8080 only                      │     │
│  │  • api → postgres : port 5432 only                          │     │
│  │  • deny all ingress par défaut                              │     │
│  └─────────────────────────────────────────────────────────────┘     │
└──────────────────────────────────────────────────────────────────────┘

         ▲ GitOps (pull)                    ▲ Push image
         │                                  │
┌────────┴────────────┐          ┌──────────┴───────────────────────┐
│  ArgoCD             │          │  GitHub Actions CI               │
│  (sync k8s/ → GKE)  │          │  • Maven test + Cucumber         │
│  • Auto-sync        │          │  • SonarCloud analysis           │
│  • Health checks    │          │  • Trivy image scan              │
│  • Rollback auto    │          │  • Contract tests                │
└─────────────────────┘          │  • Playwright E2E                │
                                 │  • Docker build + push AR        │
                                 └──────────────────────────────────┘
```

---

## 🧩 Composants kube-train modifiés/étendus

| Composant | Modifications |
|-----------|--------------|
| `kube-train-api/` | Flyway migrations, Outbox table, OAuth2 Resource Server, Contract Producer, Cucumber steps, OpenTelemetry agent |
| `train-notification-service/` | Contract Consumer stubs, OpenTelemetry agent, Feign client authentifié |
| `k8s/` | NetworkPolicies, ArgoCD Application manifest, OTel Collector DaemonSet |
| `.github/workflows/` | SonarCloud step, Trivy scan, Contract tests, Playwright E2E, suppression du `kubectl apply` (remplacé par ArgoCD) |
| `docker-compose.yml` | Ajout Jaeger (local tracing), Keycloak (local OAuth2) |
| Nouveau : `e2e/` | Tests Playwright ou REST-assured E2E |
| Nouveau : `bdd/` | Features Cucumber (.feature) + step definitions |

---

## 📅 Programme détaillé

---

### 🗓️ J1 — Flyway, Outbox Pattern & Contract Testing

> Rattrapage des sujets planifiés en Formation 2 : solidifier la couche données et les contrats inter-services.

#### Matin — Flyway & Outbox Pattern

| # | Activité | Détail |
|---|----------|--------|
| 1 | **Flyway — migrations versionnées** | Ajouter `flyway-core` au POM. Créer `V1__init_schema.sql` (tables trains, reservations). Configurer `spring.flyway.*` pour Cloud SQL. Vérifier que le schéma se crée automatiquement au démarrage. |
| 2 | **Outbox Pattern** | Créer `V2__outbox_table.sql`. Implémenter `OutboxEvent` entity + `OutboxPublisher` qui écrit dans la table dans la même transaction que la réservation. Scheduler (`@Scheduled`) ou Debezium-lite qui poll la table et publie sur Kafka. Supprimer le publish direct actuel. |
| 3 | **Test d'intégrité** | Test d'intégration : créer une réservation → vérifier que l'outbox est peuplé → vérifier la publication Kafka (Testcontainers). |

#### Après-midi — Spring Cloud Contract

| # | Activité | Détail |
|---|----------|--------|
| 4 | **Contract côté Producer** | Ajouter `spring-cloud-contract-verifier` au POM de `kube-train-api`. Écrire un contrat Groovy/YAML pour `GET /trains/{id}` et `POST /reservations`. Générer les tests auto. |
| 5 | **Contract côté Consumer** | Dans `train-notification-service`, ajouter `spring-cloud-contract-stub-runner`. Écrire un test qui consomme le stub WireMock généré par le producer. |
| 6 | **Intégration CI** | Ajouter une étape `mvn verify -pl kube-train-api` dans le workflow GitHub Actions. Le build échoue si un contrat est cassé. |

#### 📦 Livrables J1
- `kube-train-api/src/main/resources/db/migration/V1__init_schema.sql`
- `kube-train-api/src/main/resources/db/migration/V2__outbox_table.sql`
- `OutboxEvent.java`, `OutboxPublisher.java`, `OutboxPoller.java`
- Contrats `.groovy` ou `.yml` dans `kube-train-api/src/test/resources/contracts/`
- Tests d'intégration contract côté consumer
- Pipeline CI mise à jour avec étape contract-test

---

### 🗓️ J2 — OpenTelemetry & Observabilité distribuée

> Traçabilité bout-en-bout entre l'API et le notification-service, avec corrélation Kafka.

#### Matin — Instrumentation OpenTelemetry

| # | Activité | Détail |
|---|----------|--------|
| 1 | **Agent OTel Java** | Télécharger l'agent OpenTelemetry Java. Configurer via variables d'environnement (`OTEL_SERVICE_NAME`, `OTEL_EXPORTER_OTLP_ENDPOINT`). Ajouter au Dockerfile des deux services (javaagent). |
| 2 | **Propagation Kafka** | Vérifier que le `traceparent` header est propagé automatiquement par l'agent sur les messages Kafka. Tester la corrélation des spans producer → consumer. |
| 3 | **Local : Jaeger** | Ajouter Jaeger all-in-one au `docker-compose.yml`. Visualiser un trace complet : HTTP request → Kafka publish → Kafka consume → traitement notification. |

#### Après-midi — Cloud Trace & Collecteur OTel sur GKE

| # | Activité | Détail |
|---|----------|--------|
| 4 | **OTel Collector sur GKE** | Déployer un OTel Collector (DaemonSet ou Sidecar) configuré pour exporter vers Cloud Trace. Manifest dans `k8s/observability/otel-collector.yaml`. |
| 5 | **Métriques custom** | Ajouter un compteur custom (`reservations.created`) et un histogramme (`reservation.processing.duration`). Exposer via Prometheus endpoint + scrape par le collector. |
| 6 | **Dashboard Cloud Monitoring** | Créer un dashboard (ou documenter la création manuelle) montrant : latence P95, taux d'erreur, traces lentes. Lier avec les SLI/SLO de la Formation 2. |

#### 📦 Livrables J2
- `docker-compose.yml` mis à jour (Jaeger)
- Dockerfiles modifiés (javaagent OTel)
- `k8s/observability/otel-collector.yaml` (DaemonSet + ConfigMap)
- Variables d'environnement OTel dans `deployment-gke.yaml`
- Métriques custom dans `TrainService`
- Screenshot ou export du dashboard Cloud Monitoring

---

### 🗓️ J3 — ArgoCD & GitOps

> Passer d'un déploiement push (kubectl apply dans CI) à un modèle pull GitOps avec ArgoCD.

#### Matin — Installation & Configuration ArgoCD

| # | Activité | Détail |
|---|----------|--------|
| 1 | **Installer ArgoCD sur GKE** | `kubectl create namespace argocd` + install manifests officiels. Exposer l'UI via port-forward ou Ingress dédié. |
| 2 | **Application ArgoCD** | Créer `k8s/argocd/application.yaml` pointant sur le repo GitHub, path `k8s/`, branche `main`. Configurer auto-sync + auto-prune. |
| 3 | **Sync & Health Checks** | Vérifier que le changement d'un manifest dans `k8s/` déclenche un sync automatique. Observer les health checks (Progressing → Healthy). |

#### Après-midi — CI sans kubectl + Rollback

| # | Activité | Détail |
|---|----------|--------|
| 4 | **Refactoring pipeline CI** | Supprimer les étapes `kubectl apply` du workflow GitHub Actions. La CI ne fait plus que : test → build → push image → commit le nouveau tag dans `deployment-gke.yaml`. ArgoCD détecte le changement et déploie. |
| 5 | **Image Updater (optionnel)** | Configurer ArgoCD Image Updater pour détecter automatiquement les nouvelles images dans Artifact Registry (sans commit de tag). |
| 6 | **Rollback** | Simuler un déploiement cassé (image inexistante). Observer le rollback automatique ArgoCD. Documenter la procédure manuelle via CLI `argocd app rollback`. |

#### 📦 Livrables J3
- ArgoCD installé et accessible sur le cluster
- `k8s/argocd/application.yaml`
- Workflow CI simplifié (sans kubectl apply)
- Documentation rollback dans `docs/`
- Démo : push un manifest → sync automatique visible dans l'UI ArgoCD

---

### 🗓️ J4 — Sécurité applicative & réseau

> Sécuriser l'API (authentification tokens), le réseau (NetworkPolicies) et le pipeline (scan).

#### Matin — OAuth2 Resource Server & Feign authentifié

| # | Activité | Détail |
|---|----------|--------|
| 1 | **Keycloak local** | Ajouter Keycloak au `docker-compose.yml`. Créer un realm `kube-train`, un client `kube-train-api`, un user de test. |
| 2 | **OAuth2 Resource Server** | Ajouter `spring-boot-starter-oauth2-resource-server` à l'API. Configurer `spring.security.oauth2.resourceserver.jwt.issuer-uri`. Protéger `/reservations` et `/secure` (rôle `ROLE_USER`). Laisser `/trains` public. |
| 3 | **Feign client authentifié** | Dans `train-notification-service`, configurer un Feign client qui appelle l'API avec un token service-account (client_credentials grant). Interceptor `OAuth2FeignRequestInterceptor`. |

#### Après-midi — Network Policies, OWASP & Scan d'images

| # | Activité | Détail |
|---|----------|--------|
| 4 | **Network Policies** | Créer `k8s/network/network-policy-default-deny.yaml` (deny all ingress). Puis `k8s/network/network-policy-api.yaml` (allow ingress depuis ingress-controller). `k8s/network/network-policy-notification.yaml` (allow depuis api uniquement). Tester avec `kubectl exec` + `curl`. |
| 5 | **OWASP headers & rate limiting** | Ajouter un filtre Spring Security pour les headers (`X-Content-Type-Options`, `X-Frame-Options`, `Strict-Transport-Security`). Configurer un rate limiter basique (Bucket4j ou Spring Cloud Gateway si applicable). |
| 6 | **Trivy image scan dans CI** | Ajouter une étape `aquasecurity/trivy-action` dans GitHub Actions. Faire échouer le build si vulnérabilités CRITICAL ou HIGH. Scanner l'image Docker après build. |

#### 📦 Livrables J4
- `docker-compose.yml` mis à jour (Keycloak)
- Configuration OAuth2 dans `application.yml` des deux services
- `SecurityConfig.java` avec règles d'autorisation
- `k8s/network-policy-*.yaml` (3 fichiers minimum)
- Étape Trivy dans `.github/workflows/deploy.yml`
- Test manuel : requête sans token → 401, avec token → 200

---

### 🗓️ J5 — Qualité : BDD, E2E & SonarCloud

> Compléter la pyramide de tests et intégrer l'analyse qualité continue.

#### Matin — Cucumber BDD & Tests E2E

| # | Activité | Détail |
|---|----------|--------|
| 1 | **Cucumber setup** | Ajouter `cucumber-java` + `cucumber-spring` au POM. Créer `src/test/resources/features/reservations.feature` avec scénarios Gherkin en français. Implémenter les step definitions. |
| 2 | **Scénarios métier** | Écrire au minimum : "Réserver un train avec places disponibles", "Réserver un train complet → erreur", "Consulter une réservation existante". Chaque scénario exécutable via `mvn test`. |
| 3 | **Tests E2E (Playwright ou REST-assured)** | Créer un module `e2e/` avec des tests REST-assured (ou Playwright si UI future) qui ciblent l'API déployée sur GKE. Authentification via token Keycloak. Exécution dans la CI après déploiement (smoke tests). |

#### Après-midi — SonarCloud & Quality Gate

| # | Activité | Détail |
|---|----------|--------|
| 4 | **SonarCloud setup** | Connecter le repo GitHub à SonarCloud. Configurer `sonar-project.properties` (ou via Maven plugin). Définir le Quality Gate : coverage > 60%, 0 bugs, 0 vulnérabilités, < 5% code smells. |
| 5 | **Intégration CI** | Ajouter l'étape `sonarcloud-github-action` au workflow. Le build échoue si le Quality Gate n'est pas passé. Configurer le cache Maven pour la rapidité. |
| 6 | **Couverture & refactoring** | Identifier les zones non couvertes (SonarCloud report). Ajouter des tests unitaires ciblés pour passer le Quality Gate. Corriger les éventuels code smells détectés. |

#### 📦 Livrables J5
- `bdd/` ou `src/test/resources/features/*.feature` + step definitions
- `e2e/` module avec tests REST-assured
- `sonar-project.properties` ou config Maven Sonar
- Workflow CI avec étapes SonarCloud + E2E post-deploy
- Quality Gate PASSED visible sur SonarCloud
- Badge SonarCloud dans le README

---

## 🎯 Récapitulatif des compétences acquises

| Journée | Compétences |
|---------|-------------|
| J1 | Flyway, Outbox Pattern, Spring Cloud Contract, Consumer-Driven Contracts |
| J2 | OpenTelemetry, Distributed Tracing, Cloud Trace, Métriques custom, Observabilité |
| J3 | ArgoCD, GitOps, Déploiement pull-based, Rollback automatique |
| J4 | OAuth2 Resource Server, Feign authentifié, Network Policies, Trivy, OWASP |
| J5 | Cucumber BDD, REST-assured E2E, SonarCloud, Quality Gates |

---

## ⚡ Prérequis avant de commencer

- [x] Formation 2 terminée (GKE fonctionnel, CI/CD en place, Kafka opérationnel)
- [ ] Cloud SQL instance active (ou relancée) avec connectivité depuis le cluster
- [ ] Budget GCP suffisant (~50€ pour 5 jours avec ArgoCD + Keycloak + OTel Collector)
- [ ] Compte SonarCloud créé et lié au repo GitHub
- [ ] Accès admin au cluster GKE (pour installer ArgoCD et NetworkPolicies)

---

## 📚 Ressources recommandées

- [ArgoCD Getting Started](https://argo-cd.readthedocs.io/en/stable/getting_started/)
- [Spring Cloud Contract Reference](https://docs.spring.io/spring-cloud-contract/reference/)
- [OpenTelemetry Java Agent](https://opentelemetry.io/docs/languages/java/automatic/)
- [Flyway Documentation](https://documentation.red-gate.com/flyway)
- [Spring Security OAuth2 Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/)
- [Kubernetes Network Policies](https://kubernetes.io/docs/concepts/services-networking/network-policies/)
- [SonarCloud GitHub Integration](https://docs.sonarsource.com/sonarcloud/getting-started/github/)
- [Cucumber JVM](https://cucumber.io/docs/installation/java/)
- [Trivy Container Scanning](https://aquasecurity.github.io/trivy/)
