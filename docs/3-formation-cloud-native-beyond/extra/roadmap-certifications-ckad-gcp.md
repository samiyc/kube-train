# Roadmap Certifications — CKAD & GCP

> Parcours recommandé pour un profil senior Java/cloud-native (10+ ans XP), basé sur kube-train.

---

## 🗺️ Ordre recommandé

```
Formation F3 terminée
     │
     ▼
1. CKAD (~$445)              ← Dev-focused hands-on, meilleur ratio effort/valeur
     │ (3-4 semaines prépa)
     ▼
2. GCP Professional DevOps Engineer ($200)  ← Directement aligné avec kube-train
     │ (4-6 semaines prépa)
     ▼
3. GCP Professional Cloud Developer ($200)  ← Complète le stack dev
     │ (3-4 semaines — capitalise sur #2)
     ▼
4. GCP Professional Cloud Architect ($200)  ← Si besoin titre Architect/Tech Lead
```

---

## 📋 CKAD — Certified Kubernetes Application Developer

| Attribut | Détail |
|----------|--------|
| **Coût** | $445 (1 retake + 2 sessions Killer.sh incluses) |
| **Format** | Hands-on CLI (15-20 tâches), 2 heures |
| **Score** | ≥ 66% pour passer |
| **Open-book** | kubernetes.io/docs autorisé |
| **Validité** | 2 ans |

### Domaines et poids

| Domaine | Poids | Couvert en F4 |
|---------|-------|---------------|
| Application Design & Build | 20% | J2 (Jobs, CronJobs, init containers) |
| Application Deployment | 20% | J2 (Helm), J4 (canary) |
| Observability & Maintenance | 15% | J5 (SLOs, dashboards, debugging) |
| **Config & Security** | **25%** | **J1** (RBAC, PSS, securityContext, LimitRange) |
| Services & Networking | 20% | J4 (Istio, traffic management) |

### Ce que kube-train couvre déjà vs gaps

| Sujet CKAD | Déjà fait ? | Gap action |
|------------|-------------|------------|
| Deployments, Services, Ingress | ✅ F2+F3 | — |
| NetworkPolicies | ✅ F3-J4 | — |
| ConfigMaps, Secrets | ✅ F2 | — |
| Probes (liveness/readiness/startup) | ✅ F2 | — |
| **Helm** | ⚠️ F2 (consommateur) | F4-J2 (créateur) |
| **RBAC** (Roles, RoleBindings) | ❌ | F4-J1 |
| **Jobs / CronJobs** | ❌ | F4-J2 |
| **Init containers** | ❌ | F4-J1 |
| **LimitRange / ResourceQuota** | ❌ | F4-J1 |
| Security contexts (non-root, read-only FS) | ❌ | F4-J1 |
| Multi-container patterns (sidecar) | ⚠️ Cloud SQL Proxy | Documenter |

---

## 📋 GCP Professional Cloud DevOps Engineer

| Attribut | Détail |
|----------|--------|
| **Coût** | $200 |
| **Format** | 50-60 MCQ/multi-select, 2 heures |
| **XP requis** | 3+ ans industrie, 1+ an GCP production |
| **Validité** | 2 ans |

### Domaines de l'examen

| Domaine | Couvert en F4 |
|---------|---------------|
| Bootstrapping & maintenance infra GCP | J3 (Terraform) |
| SRE practices (SLI, SLO, error budgets) | J5 |
| CI/CD pipelines (Cloud Build, Cloud Deploy) | J3 (pipeline Terraform), J4 (canary) |
| Observability & troubleshooting | J5 (Cloud Monitoring, alertes) |
| Cost & performance optimization | J1 (ResourceQuota), J3 (Autopilot) |

### Pourquoi c'est la meilleure certif GCP pour ce profil

- Couvre exactement ce que kube-train démontre : GKE, CI/CD, SRE, IaC
- Différenciateur fort sur le marché consulting cloud-native
- Se prépare naturellement après la Formation F4

---

## 📋 GCP Professional Cloud Developer

| Attribut | Détail |
|----------|--------|
| **Coût** | $200 |
| **Format** | 50-60 MCQ, 2 heures |
| **Mise à jour 2026** | Inclut Gen AI APIs, AI coding assistants |

### Domaines

1. Design d'applications cloud-native scalables et sécurisées
2. Build & test (TDD, contract testing, CI)
3. Configuration pour déploiement (K8s, Helm, Cloud Run, traffic management)
4. Intégration services GCP (Pub/Sub, Cloud SQL, Secret Manager, Eventarc, IAM)

→ Se prépare rapidement après la certif DevOps Engineer car beaucoup de recoupement.

---

## 📋 GCP Professional Cloud Architect (optionnel)

| Attribut | Détail |
|----------|--------|
| **Coût** | $200 (standard), $100 (renouvellement) |
| **Format** | 50-60 MCQ + **2 études de cas** (20-30% de l'examen) |
| **Framework** | Google Cloud Well-Architected Framework (6 piliers) |

Requiert une vision plus large (migration legacy, multi-cloud, hybrid). Pertinent si on vise un rôle Tech Lead / Architect.

---

## ⏱️ Planning réaliste

| Semaine | Activité |
|---------|----------|
| S1-S2 | Formation F4 (J1-J5) |
| S3-S4 | Consolidation F4 (NotebookLM + Anki) |
| S5-S7 | Prépa CKAD (Killer.sh, Play with K8s, docs.kubernetes.io) |
| S8 | **Passage CKAD** |
| S9-S12 | Prépa GCP DevOps Engineer (Cloud Skills Boost, sample exams) |
| S13 | **Passage GCP DevOps** |
| S14-S16 | Prépa GCP Developer (capitalise sur DevOps) |
| S17 | **Passage GCP Developer** |

---

## 💡 Tips certifications

- **CKAD** : vitesse cruciale. Maîtriser `kubectl create --dry-run=client -o yaml`, aliases, vim.
- **GCP DevOps** : bien connaître Cloud Build, Cloud Deploy, SLO API, Error Budget policies.
- **Tous** : les questions pièges testent la compréhension du "pourquoi", pas juste du "comment".
- **Killer.sh** : ne pas le faire trop tôt — attendre d'avoir couvert 80% du programme.

---

*Dernière mise à jour : 2026-05-30*
