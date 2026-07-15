# 📊 Bilan — Formation F4 : Platform Engineering (5 jours)

**Dates** : juin – juillet 2026 (J1 04/06 → J5 08/07)
**Fil rouge** : transformer kube-train en plateforme production-ready démontrant des compétences senior certifiables (CKAD + GCP DevOps).

---

## 🎯 Scores

| Jour | Sujet | QCM | Examen ouvert |
|------|-------|-----|---------------|
| J1 | Sécurité K8s & RBAC | **8/8** (04/06) | 6,5/10 (09/06) |
| J2 | Helm & Packaging | **8/8** (10/06) | 7/10 (10/06) |
| J3 | Terraform IaC | **6/8** (23/06) | — |
| J4 | Istio Service Mesh | **8/8** (25/06) | — |
| J5 | SRE & Observabilité | **7/8** (08/07) | — |

**Moyenne QCM** : 37/40 (92,5 %) — objectif ≥ 6/8 par jour **atteint sur les 5 jours**.
Point faible ponctuel : J3 (Terraform) à 6/8 — le sujet le plus dense en nouveaux concepts (state, backend, import, Workload Identity Federation).

---

## Ce qui a été accompli

### Livrables techniques

| Jour | Sujet | Livrable concret |
|------|-------|------------------|
| J1 | Sécurité K8s & RBAC | `securityContext` restricted (non-root, read-only FS, drop ALL), ServiceAccount dédié `kube-train-api-sa` + Role/RoleBinding, PSS (enforce baseline / audit-warn restricted), LimitRange + ResourceQuota, init container Flyway |
| J2 | Helm & Packaging | Chart `kube-train-chart/` complet (templates, `_helpers.tpl`, `NOTES.txt`), values multi-env (`values-minikube.yaml` / `values-gke.yaml`), CronJob templaté, `helm upgrade --atomic` + rollback |
| J3 | Terraform IaC | `infra/` complet (GKE, Cloud SQL, IAM/WI, Pub/Sub, Artifact Registry, APIs), backend GCS, pipeline PR→plan / merge→apply |
| J4 | Istio Service Mesh | mTLS STRICT (PeerAuthentication), canary 90/10 (VirtualService + DestinationRule), AuthorizationPolicy par identité SPIFFE, fault injection (delay + abort) |
| J5 | SRE & Observabilité | 2 SLOs (availability request-based + latency window-based) via API REST, alerte burn rate 14,4×, dashboard Golden Signals, Gatekeeper (2 ConstraintTemplates + Constraints) |

### Infra & outillage produits
- **Helm chart** réutilisable, testé sur Minikube et GKE (rendu `helm template` validé sur les deux overlays).
- **Terraform** provisionne l'intégralité de l'infra GCP (zéro clic Console) avec state distant verrouillé.
- **Cloud Service Mesh** (Istio managé via GKE Fleet / Traffic Director) : sidecars Envoy injectés, mTLS inter-services.
- **Observabilité SRE** : pipeline OTel Collector → Cloud Monitoring, SLOs + burn rate + dashboard, policy-as-code Gatekeeper.

### Compétences acquises (discours entretien)
- Packaging Kubernetes de niveau créateur (Helm templating, values multi-env, release lifecycle) — pas juste consommateur.
- Infrastructure as Code disciplinée (Terraform, backend distant, import de l'existant, pipeline GitOps infra).
- Service mesh production : mTLS zero-trust, progressive delivery (canary), autorisation L7 par identité.
- SRE appliqué : implémentation programmatique de SLOs, alerting burn rate multi-window, dashboards golden signals.
- Policy-as-code : admission control Gatekeeper (registres autorisés, resource limits obligatoires).

---

## 🔎 Re-check des 12 facteurs — Point de situation F2 → F4

> Audit initial réalisé en F2-J4 (`docs/2-formation-cloud-native/formation-cloud-native-notes.md`). F4 n'a pas changé le code applicatif mais a **renforcé le socle plateforme** autour de plusieurs facteurs.

| Factor | F2 | F4 | Ce que F4 apporte |
|--------|----|----|-------------------|
| 1 — Codebase | ✅ | ✅ | Inchangé (1 repo, N déploiements) |
| 2 — Dependencies | ✅ | ✅➕ | Helm chart + Terraform : dépendances **d'infra** aussi déclarées explicitement |
| 3 — Config | ✅ | ✅➕ | `values-minikube/gke.yaml` formalisent la config par environnement (avant : env vars éparses) |
| 4 — Backing services | ✅ | ✅ | Cloud SQL / Pub/Sub toujours attachés par config |
| 5 — Build / Release / Run | ✅ | ✅➕ | Release lifecycle **explicite** via Helm (`upgrade --atomic`, `rollback`, `history`) |
| 6 — Processes (stateless) | ⚠️ | ⚠️ | **Inchangé** — dette isolée au profil `default` local (Map en mémoire). Prod = Cloud SQL, stateless. Seule dette assumée. |
| 7 — Port binding | ✅ | ✅ | Inchangé (Tomcat embarqué) |
| 8 — Concurrency | ✅ | ✅➕ | HPA templaté (Helm) + scaling horizontal ; ResourceQuota encadre |
| 9 — Disposability | ✅ | ✅ | Probes (startup/liveness/readiness) + graceful shutdown |
| 10 — Dev/prod parity | ✅ | ✅➕ | Même chart, mêmes manifests templatés dev↔prod (écart réduit) |
| 11 — Logs | ✅ | ✅➕ | + OTel Collector / Cloud Trace (tracing distribué en plus des logs) |
| 12 — Admin processes | ✅ | ✅➕ | Jobs/CronJobs Helm (outbox poller, tâches one-shot) formalisés |

**Transverse — sécurité (renforcement majeur F4)** : PSS + RBAC dédié + Gatekeeper + mTLS Istio. Le durcissement sécurité est le gain le plus visible de F4 par rapport à F2 (Secret Manager + HTTPS seulement).

**Verdict** : 11/12 facteurs pleinement respectés ; le Factor 6 reste la seule dette, volontairement circonscrite au profil de dev local.

---

## Points forts de la formation

1. **Format "6 drills + escalier"** — TP en 4-5 étapes progressives : faire, se tromper, corriger. Beaucoup plus ancrant que du pas-à-pas guidé.
2. **QCM quotidien** (retour de F2) — validation progressive, 92,5 % de moyenne.
3. **Ajout des examens ouverts** (J1, J2) — questions type entretien, révèlent les zones floues qu'un QCM masque (J1 ouvert 6,5/10 vs QCM 8/8).
4. **Stack production réaliste** — Helm, Terraform, Istio managé, SLO API : exactement l'outillage de mission.
5. **Blocages réels documentés** — chaque galère (WI Autopilot, `gcloud monitoring slos` inexistant, fault injection intra-mesh) → anecdote entretien + entrée runbook.

---

## Points d'amélioration / retours

### 🟡 J3 Terraform — le plus difficile (6/8)
Le jour le plus dense : state, backend, import, Workload Identity Federation en une journée. À reprendre en révision avant la certif GCP DevOps.

### 🟡 SLO latence — proxy et non vrai P95
Le SLO latence implémenté utilise `http_server_requests_seconds_max` (proxy), pas un vrai P95, car Micrometer n'exporte pas les buckets d'histogramme par défaut. **À corriger** : activer `management.metrics.distribution.percentiles-histogram.http.server.requests=true`. Documenté en `J5-sre-observabilite/notes-J5.md` §10.

### 🟡 Examens ouverts abandonnés après J2
Les examens ouverts (excellents pour la profondeur) n'ont été faits qu'en J1/J2. **F5** : les systématiser (un par jour).

### 🟢 Ce qui a bien marché
- Séparation QCM / réponses / corrections datées → traçabilité de la progression.
- Notes avec sections "blocages rencontrés" → réutilisables en runbook et en entretien.
- Validation E2E de fin de journée (J5) → preuve concrète que tout fonctionne bout-en-bout.

---

## 🚧 Éléments manquants / à voir en suivant

| Sujet | Action | Priorité |
|-------|--------|----------|
| Vrai P95 latence | Activer `percentiles-histogram` + recréer le SLO latency sur les buckets | Moyenne |
| `docs/readme.md` absent | Référencé par `CLAUDE.md` mais inexistant → créer (roadmap + cheat-sheet) ou corriger la référence | Basse |
| Rétention QCM F4 | Re-passer les 5 QCM dans 2-3 semaines pour mesurer la mémorisation | Moyenne |
| Gaps CKAD | Vitesse impérative kubectl, Kustomize, patterns multi-container → **traités en F5** | Haute |
| Réorg repo | Split runbook F4 par jour + sous-dossiers `k8s/` par concern | ✅ Fait (10/07) |
| SLO/alerte/dashboard as-code | Créés via API REST → passer en Terraform (`google_monitoring_*`) pour reproductibilité | Basse (lab GCP) |
| Trace distribuée E2E | Propagation via Outbox api→notification + SA OTel dédié + idempotence | ✅ Fait (15/07) |
| Budget avant vacances | `terraform destroy` + stop Cloud SQL **vendredi soir** (départ 24/07, fin d'essai GCP) | Haute |

---

## Prochaines étapes

1. **F5 — Préparation CKAD** (8 jours, local-first Minikube) : drills par domaine + examens blancs chronométrés. Voir `docs/5-formation-ckad-prep/formation-ckad-prep-plan.md`.
2. **Passage CKAD** puis **GCP Professional Cloud DevOps Engineer** (voir `docs/3-.../extra/roadmap-certifications-ckad-gcp.md`).
3. **Nettoyage GCP** avant vacances (destroy infra, stop Cloud SQL) — bascule local-first en août.
4. Préparer le discours entretien à partir des notes J1-J5 + des blocages documentés.

---

## Architecture finale F4

```
┌─ GitHub ───────────────────────────────────────────────────────────────────────┐
│  PR → terraform plan (commentaire)   │   push main → deploy.yml (CI/CD)        │
│  merge → terraform apply             │   test → build → deploy (Helm/kubectl)  │
└────────────────────────────────────────────────────────────────────────────────┘
                   │
                   ▼
┌─ GKE Autopilot (europe-west1) — provisionné par Terraform ─────────────────────┐
│                                                                                │
│  Cloud Service Mesh (Istio managé — Traffic Director)                          │
│  ┌─ kube-train-api (3/3) ────────┐            ┌─ notification (2/2) ───────┐   │
│  │ api + cloud-sql-proxy + envoy │    mTLS    │ notif-container + envoy    │   │
│  │ securityContext restricted    │◄─ STRICT ─►│ AuthorizationPolicy SPIFFE │   │
│  │ SA dédié + RBAC               │            └────────────────────────────┘   │
│  │ VirtualService canary 90/10   │                                             │
│  └───────────────┬───────────────┘                                             │
│                  ▼                                                             │
│  Cloud SQL (Terraform) · Pub/Sub (Terraform) · Artifact Registry               │
│                                                                                │
│  Observabilité : OTel Collector → Cloud Monitoring                             │
│    └─ 2 SLOs + alerte burn rate 14,4× + dashboard Golden Signals               │
│  Policy : Gatekeeper (registres autorisés + resource limits requis)            │
│  Packaging : Helm chart (values-minikube / values-gke)                         │
└────────────────────────────────────────────────────────────────────────────────┘
```

---

*Dernière mise à jour : 2026-07-08*
