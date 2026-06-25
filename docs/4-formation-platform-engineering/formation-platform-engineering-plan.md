# Formation F4 — Platform Engineering & Certifications

> **Fil rouge** : Transformer kube-train en plateforme production-ready démontrant des compétences senior certifiables (CKAD + GCP Professional DevOps Engineer).

**Prérequis** : Formation F3 complétée (ArgoCD, OTel, OAuth2, NetworkPolicies, SonarCloud, Trivy).

---

## 🎯 Objectifs de la formation

1. **Combler les gaps CKAD** identifiés en F3 (RBAC, Helm, Jobs, init containers, security contexts)
2. **Infrastructure as Code** — tout provisionner via Terraform (plus de `gcloud` artisanal)
3. **Service Mesh** — Istio pour le mTLS, canary et observabilité réseau
4. **SRE** — SLOs, error budgets, alertes sur burn rate
5. **Progressive Delivery** — promotion multi-environnement avec rollback automatique

---

## 📋 Format pédagogique

| Élément | Description |
|---------|-------------|
| **Durée** | 5 jours (J1-J5) |
| **Matin** | Théorie + démo guidée (config en place avec exemple simple) |
| **Après-midi** | TP en 4-5 étapes progressives (reproduire + étendre) |
| **Fin de journée** | QCM 8 questions + correction |
| **Livrables** | Runbook + notes de révision mis à jour chaque jour |

**Principe TP** : Chaque TP part d'un exemple fonctionnel minimal, puis ajoute 4-5 étapes de complexité croissante. L'apprenant fait, se trompe, corrige — et intègre les concepts en pratiquant.

---

## 📅 Programme détaillé

---

### J1 — Sécurité Kubernetes & RBAC (aligné CKAD 25%)

**Objectif** : Sécuriser les pods kube-train selon les standards production et maîtriser RBAC.

**Matin — Théorie :**
- Pod Security Standards (PSS) : Privileged / Baseline / Restricted
- `securityContext` : `runAsNonRoot`, `readOnlyRootFilesystem`, `allowPrivilegeEscalation: false`, `drop: [ALL]`
- ServiceAccounts dédiés (1 par workload vs `default`)
- RBAC : Role, RoleBinding, ClusterRole, ClusterRoleBinding
- LimitRange et ResourceQuota (contraintes namespace-level)
- Init containers : pattern et cas d'usage (migration DB, attente dépendance)

**Après-midi — TP (5 étapes) :**
1. Ajouter `securityContext` complet sur le pod API (non-root, read-only FS, drop ALL)
2. Créer un ServiceAccount dédié `kube-train-api-sa` + Role/RoleBinding (accès secrets uniquement)
3. Ajouter un init container Flyway pour les migrations DB (remplace le mode auto-DDL)
4. Appliquer PSS `enforce: baseline` + `audit: restricted` sur le namespace
5. Configurer LimitRange (min/max CPU/mem) + ResourceQuota sur le namespace

**QCM J1** : securityContext, RBAC, PSS, init containers, LimitRange

**Compétences CKAD couvertes** : Application Environment, Configuration & Security (25%)

---

### J2 — Helm & Packaging Kubernetes

**Objectif** : Packager kube-train en chart Helm pour éliminer la duplication de manifests et préparer le multi-environnement.

> ⚠️ **Rappel F2** : Helm a été utilisé en F2 uniquement comme outil d'installation (`helm install` cert-manager, Prometheus). Ici on passe à la **création de charts** : templating, values multi-env, release lifecycle — c'est un tout autre niveau de maîtrise.

**Matin — Théorie :**
- Helm : chart structure (`Chart.yaml`, `values.yaml`, `templates/`)
- Templating Go : `{{ .Values.image.tag }}`, `{{ if }}`, `{{ range }}`
- Release lifecycle : `helm install`, `helm upgrade --atomic`, `helm rollback`
- Values per environnement : `values-dev.yaml`, `values-prod.yaml`
- Jobs & CronJobs : outbox poller, cleanup tasks, DB backup
- Helm + ArgoCD : Application source type `helm`

**Après-midi — TP (5 étapes) :**
1. `helm create kube-train` → nettoyer le scaffold, migrer `deployment-gke.yaml` en template
2. Paramétrer : `image.tag`, `replicas`, `resources`, `env` via `values.yaml`
3. Créer `values-minikube.yaml` et `values-gke.yaml` → tester les deux
4. Ajouter un CronJob Helm template : outbox poller (toutes les 5 min) ou DB vacuum
5. Intégrer le chart dans ArgoCD (`Application` source type Helm, valeurs overridées)

**QCM J2** : Helm commands, templating, release lifecycle, Jobs/CronJobs, ArgoCD+Helm

**Compétences CKAD couvertes** : Application Deployment (20%), multi-container patterns

---

### J3 — Terraform & Infrastructure as Code (GCP)

**Objectif** : Provisionner l'intégralité de l'infrastructure kube-train via Terraform — zéro clic Console.

**Matin — Théorie :**
- Terraform : provider, resource, data, variable, output, state
- Backend GCS pour le state (verrouillage + historique)
- Modules Terraform GCP (google_container_cluster, google_sql_database_instance, etc.)
- Workload Identity Federation : setup complet via Terraform
- Workflow GitOps infra : PR → `terraform plan` (commentaire) → merge → `terraform apply`
- Import de ressources existantes (`terraform import`)

**Après-midi — TP (5 étapes) :**
1. Init Terraform + provider GCP + backend GCS → importer le cluster GKE existant
2. Coder le cluster GKE Autopilot + VPC en HCL (reproduire l'existant)
3. Ajouter Cloud SQL + IAM Workload Identity + Secret Manager
4. Ajouter Artifact Registry + Pub/Sub (topics + subscriptions)
5. Pipeline GitHub Actions : `terraform plan` sur PR, `terraform apply` sur merge main

**QCM J3** : Terraform lifecycle, state, import, modules, Workload Identity, backend GCS

**Compétences GCP DevOps** : Bootstrapping infra, IaC discipline, IAM

---

### J4 — Istio Service Mesh & Progressive Delivery

**Objectif** : Ajouter un service mesh pour le mTLS automatique, le traffic management et le canary deployment.

**Matin — Théorie :**
- Istio architecture : data plane (Envoy sidecar) + control plane (Istiod)
- CRDs : VirtualService, DestinationRule, Gateway, PeerAuthentication, AuthorizationPolicy
- mTLS STRICT : chiffrement inter-services automatique
- Canary deployment : traffic split 90/10 puis rollout progressif
- Fault injection : tester la résilience (delay, abort)
- Cloud Service Mesh (managed Istio) vs self-hosted

**Après-midi — TP (4 étapes) :**
1. Installer Istio sur le cluster (Helm ou `istioctl`) + activer injection sidecar sur le namespace
2. `PeerAuthentication: STRICT` → vérifier mTLS entre API et notification-service
3. Déployer une v2 de l'API (endpoint modifié) + `VirtualService` 90/10 canary
4. `AuthorizationPolicy` : seul l'API peut appeler notification-service + fault injection test

**QCM J4** : Istio architecture, VirtualService, mTLS, canary, AuthorizationPolicy

**Compétences GCP DevOps** : Service mesh, progressive delivery, network security L7

---

### J5 — SRE en pratique : Implémentation SLOs, Alertes & Gatekeeper

**Objectif** : Implémenter concrètement les concepts SRE vus en théorie en F2 (SLI/SLO/Error Budget) — passer de la compréhension à l'implémentation production-ready.

> ⚠️ **Rappel F2** : La théorie SLI/SLO/Error Budget a été couverte en F2-J3/J4 (définitions, math, JSON Cloud Monitoring). Ici on passe à la **pratique** : créer les SLOs, configurer les alertes burn rate, et construire les dashboards.

**Matin — Théorie (ce qui est NOUVEAU vs F2) :**
- Burn rate alerting (fast burn 14.4× / slow burn 3×) — pas couvert en F2
- Multi-window multi-burn-rate alerting (Google SRE Workbook pattern)
- Cloud Monitoring SLO API : création programmatique (vs Console en F2)
- Dashboards golden signals : traffic, errors, latency, saturation (MQL queries)
- OPA/Gatekeeper : policy-as-code pour enforcement continu (admission controller)
- Cloud Load Testing : alternative GCP-native à Locust pour générer de la charge

**Après-midi — TP (5 étapes) :**
1. Définir 2 SLIs pour kube-train (availability `/trains`, latency P95 `/reservations`)
2. Créer les SLOs dans Cloud Monitoring via API (`gcloud monitoring slos create`) avec fenêtre 30 jours rolling
3. Configurer une alerte burn rate (notification si >14.4× sur 1h) + notification channel
4. Construire un dashboard MQL : golden signals + error budget remaining + heatmap latence
5. Installer Gatekeeper + 2 contraintes : images autorisées (Artifact Registry only) + resource limits requis

**QCM J5** : burn rate, SLO API, golden signals, MQL, Gatekeeper, ConstraintTemplate

**Compétences GCP DevOps** : SRE practices, observability, alerting, policy enforcement

---

## 📊 Mapping Certifications

### CKAD (Certified Kubernetes Application Developer)

| Domaine CKAD (poids) | Couvert en F4 |
|-----------------------|---------------|
| Application Design & Build (20%) | J2 (Jobs, CronJobs, init containers) |
| Application Deployment (20%) | J2 (Helm), J4 (canary) |
| Observability & Maintenance (15%) | J5 (SLOs, dashboards, debugging) |
| **Config & Security (25%)** | **J1** (RBAC, PSS, securityContext, LimitRange) |
| Services & Networking (20%) | J4 (Istio, service mesh, traffic management) |

### GCP Professional Cloud DevOps Engineer

| Domaine GCP (poids estimé) | Couvert en F4 |
|----------------------------|---------------|
| Bootstrapping infra GCP | J3 (Terraform) |
| SRE practices | J5 (SLOs, error budgets) |
| CI/CD pipelines | J3 (Terraform pipeline), J4 (canary) |
| Observability & troubleshooting | J5 (Cloud Monitoring, alertes) |
| Cost & performance | J1 (ResourceQuota), J3 (Autopilot config) |

---

## 🔄 Progression F2 → F3 → F4

| Aspect | F2 (Cloud Native) | F3 (Beyond) | F4 (Platform Engineering) |
|--------|-------------------|-------------|---------------------------|
| **Focus** | Déployer sur GCP (GKE, CI/CD, HTTPS) | Intégrer (OTel, ArgoCD, OAuth2, Qualité) | Production-ready & certifications |
| **TP style** | Pas-à-pas guidé | Config guidée pas-à-pas | Config simple → TP en escalier 4-5 étapes |
| **Helm** | Consommateur (`helm install`) | — | Créateur (chart, templating, values multi-env) |
| **SLI/SLO** | Théorie (définitions, error budget math) | — | Implémentation (SLO API, burn rate alertes, dashboards MQL) |
| **Monitoring** | Cloud Monitoring basique + Prometheus endpoint | OTel Collector + Cloud Trace | Golden signals dashboard + alertes avancées |
| **Infra** | `gcloud` Console/CLI | `gcloud` + `kubectl apply` | Terraform IaC (zéro clic) |
| **Sécurité** | Secret Manager + HTTPS | NetworkPolicies + Trivy + OAuth2 | PSS + RBAC + Gatekeeper + mTLS Istio |
| **Déploiement** | `kubectl apply` + CI/CD | ArgoCD GitOps | Canary Istio + Helm + promotion multi-env |
| **Load testing** | Locust (local) | — | Cloud Load Testing (GCP-native, alternative à Locust) |

---

## 📦 Livrables attendus en fin de F4

- [ ] Helm chart `kube-train/` fonctionnel (multi-env via values)
- [ ] Terraform `infra/` provisionne tout GKE + Cloud SQL + IAM
- [x] Istio mTLS + canary deployment fonctionnel
- [ ] 2 SLOs + alertes burn rate configurés dans Cloud Monitoring
- [ ] Tous les pods en `securityContext: restricted` + RBAC dédié
- [ ] 5 QCMs validés (score cible : ≥ 6/8 par jour)
- [ ] Runbook + notes de révision complètes

---

## 💰 Budget GCP — Objectif ≤ 5€/jour

> **Retour F3** : ArgoCD (3 pods) faisait monter la consommation à ~10€/jour.
> Les pods ArgoCD ont été supprimés. Objectif F4 : rester sous 5€/jour pour maximiser les 132€ de crédits restants.

| Ressource | Coût estimé | Optimisation |
|-----------|-------------|--------------|
| GKE Autopilot (API + notif) | ~3-4€/jour | `terraform destroy` en fin de journée |
| Cloud SQL (db-f1-micro) | ~0.25€/jour actif | Arrêter quand non utilisé (`gcloud sql instances patch --activation-policy=NEVER`) |
| Istio (surcoût Envoy sidecars) | +1-2€/jour (CPU/mem supplémentaire) | Sidecar uniquement sur namespace kube-train |
| Cloud Monitoring (SLOs) | Gratuit (< 150M API calls) | — |
| Terraform state (GCS) | ~0.02€/mois | Négligeable |
| Cloud Load Testing | Pay-per-use (~0.5€/run) | Limiter à 5 min/run |

**Stratégie coût** :
- Provisionner le matin, `terraform destroy` le soir (< 5 min avec Terraform)
- Supprimer ArgoCD quand pas utilisé (`kubectl delete -n argocd --all`)
- Cloud SQL : stopper l'instance entre les sessions
- Istio : installer J4 uniquement, supprimer après TP si besoin

---

## 🗓️ Planning suggéré

```
Semaine 1 (après F3) : Révision F3 + lecture Terraform docs
Semaine 2 : J1 (Sécurité K8s) + J2 (Helm)
Semaine 3 : J3 (Terraform)
Semaine 4 : J4 (Istio) + J5 (SRE)
Semaine 5-8 : Préparation CKAD (pratique labs)
Semaine 9-12 : Préparation GCP DevOps Engineer
```

---

*Dernière mise à jour : 2026-05-29*
