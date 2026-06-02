# 🎓 Guide des certifications — Kubernetes & GCP

> Préparé après la formation 2 (mai 2026). À lire avant de planifier les certifications.

---

## Recommandation d'ordre

```
[Fin Formation 3]
      │
      ▼
  1. CKAD (~395$)          ← Le plus adapté à ton profil dev Java
      │  (2-4 semaines de prep ciblée)
      ▼
  2. GCP Associate Cloud Engineer (~125$)   ← Complète kube-train + labs GCP
      │  (3-4 semaines)
      ▼
  3. CKA (optionnel, ~445$)   ← Seulement si évolution vers Platform/SRE
```

---

## 1. CKAD — Certified Kubernetes Application Developer

> **Priorité : ★★★★★ — À passer en premier**

| Critère | Détail |
|---------|--------|
| Organisme | CNCF (Linux Foundation) |
| Format | **Hands-on CLI** (pas QCM) — 15-20 tâches pratiques |
| Durée | 2 heures |
| Coût | ~395 $ (1 retake inclus si échec) |
| Score passage | 66% |
| Validité | 3 ans |
| Open book | ✅ docs.kubernetes.io autorisé pendant l'exam |
| Difficulté pour toi | ⭐⭐ (tu connais déjà ~70% avec kube-train) |
| ROI CV | 🔥🔥🔥 (dev Java/K8s cloud-native) |

### Domaines de l'examen

| Domaine | Poids | Couvert par kube-train |
|---------|-------|----------------------|
| Application Design & Build | 20% | ✅ Pods, Deployments, images Docker |
| Application Deployment | 20% | ✅ Rolling updates, Deployments, Helm (F3 J3) |
| Application Observability & Maintenance | 15% | ✅ Probes, Logging, Metrics (F2 J4) |
| Application Environment, Config & Security | 25% | ✅ ConfigMaps, Secrets, OAuth2 (F3 J4) |
| Services & Networking | 20% | ✅ Services, Ingress, NetworkPolicies (F3 J4) |

### ❌ Lacunes à combler avant l'examen

| Sujet manquant | Où le pratiquer |
|----------------|-----------------|
| **Jobs & CronJobs** | Ajouter un exercice simple (ex: job de nettoyage outbox) |
| **Helm** | Prévu F3 J3 (ArgoCD) — pratiquer `helm install/upgrade/rollback` |
| **RBAC détaillé** (Roles, RoleBindings, ClusterRole) | Labs Killer.sh |
| **Init containers** | Labs Killer.sh |
| **Multi-container patterns** (sidecar, adapter, ambassador) | kube-train a un sidecar Cloud SQL Proxy — approfondir |
| **Resource Requests/Limits & LimitRange** | Ajouter aux manifests k8s/ |

### Préparation recommandée
1. **Killer.sh** — 2 sessions de simulation incluses dans l'achat de l'exam (les utiliser en dernier lieu)
2. **KodeKloud CKAD course** — très bien pour la pratique en ligne
3. **kube-train** — ton meilleur terrain d'entraînement, tu connais le projet par cœur
4. Chronométrer : à 2h pour 15-20 tâches, il faut ~6-8 min/tâche max

---

## 2. GCP Associate Cloud Engineer (ACE)

> **Priorité : ★★★★☆ — Après CKAD**

| Critère | Détail |
|---------|--------|
| Organisme | Google Cloud |
| Format | 50 QCM (multiple choice/select), scenarios |
| Durée | 2 heures |
| Coût | **125 $** (le moins cher !) |
| Score passage | ~70% (non officiel) |
| Validité | 3 ans |
| Difficulté pour toi | ⭐⭐ (scope large mais QCM) |
| ROI CV | 🔥🔥🔥 (missions GCP de plus en plus demandées) |

### Domaines de l'examen

| Domaine | Poids | Couvert par kube-train |
|---------|-------|----------------------|
| Setting up cloud environment | ~17% | ✅ Projet, IAM, billing |
| Planning & configuring cloud solution | ~17% | ✅ GKE, Cloud SQL, Pub/Sub |
| Deploying & implementing | ~25% | ✅ CI/CD, kubectl, gcloud |
| Ensuring successful operations | ~20% | ✅ Cloud Logging, Monitoring, probes |
| Configuring access & security | ~20% | ✅ IAM, Workload Identity, Secret Manager |

### ❌ Lacunes à combler (scope hors kube-train)

| Sujet manquant | Ressource |
|----------------|-----------|
| **Compute Engine** (VMs, instance groups, templates) | CloudSkillsBoost lab gratuit |
| **Cloud Functions / Cloud Run** | Lab "Serverless" CloudSkillsBoost |
| **VPC, Firewall rules, Cloud Load Balancer** | Lab réseau GCP |
| **BigQuery basics** (tables, requêtes, permissions) | Lab BigQuery |
| **Cloud Storage** (buckets, IAM, lifecycle) | Déjà un peu vu mais à approfondir |
| **Billing & organisation GCP** (folders, org policies) | Documentation GCP |
| **gcloud CLI maîtrise complète** | `gcloud cheat-sheet` + pratique |

### Préparation recommandée
1. **Google CloudSkillsBoost** (anciennement Qwiklabs) — labs gratuits ou avec crédits
2. **"Associate Cloud Engineer" Study Guide** (livre O'Reilly ou Packt)
3. **Examens blancs** : whizlabs, examtopics (attention aux dumps — préférer la compréhension)
4. Ton expérience kube-train couvre ~40-50% du contenu — focus sur Compute Engine et VPC

---

## 3. CKA — Certified Kubernetes Administrator (optionnel)

> **Priorité : ★★☆☆☆ — Seulement si évolution Platform/SRE**

| Critère | Détail |
|---------|--------|
| Organisme | CNCF (Linux Foundation) |
| Format | **Hands-on CLI** — 15-20 tâches pratiques |
| Durée | 2 heures |
| Coût | ~445 $ (1 retake inclus) |
| Score passage | 66% |
| Validité | 3 ans |
| Difficulté pour toi | ⭐⭐⭐ (cluster install, kubeadm, etcd, RBAC cluster-level) |

### Domaines de l'examen

| Domaine | Poids |
|---------|-------|
| Troubleshooting | 30% |
| Cluster Architecture, Installation & Configuration | 25% |
| Services & Networking | 20% |
| Workloads & Scheduling | 15% |
| Storage | 10% |

**Sujets spécifiques CKA** (hors portée kube-train) :
- Installation cluster avec `kubeadm` (init, join worker nodes)
- Upgrade cluster Kubernetes (version N → N+1)
- Backup/restore `etcd`
- Troubleshooting cluster-level (node NotReady, scheduler, controller-manager)
- Helm & Kustomize (également dans CKAD)

→ Si tu fais CKAD en premier, tu auras ~60% des connaissances pour CKA.

---

## 💰 Budget certifications

| Certification | Coût | Ordre |
|---------------|------|-------|
| CKAD | ~395 $ ≈ ~365 € | 1er |
| GCP ACE | 125 $ ≈ ~115 € | 2ème |
| CKA (optionnel) | ~445 $ ≈ ~410 € | 3ème |
| **Total CKAD + ACE** | **~480 €** | |

---

## 📅 Planning suggéré

```
Formation 3     │  Prep CKAD  │  Exam CKAD  │  Prep ACE   │  Exam ACE
(5 jours)       │  (3 sem.)   │             │  (3 sem.)   │
────────────────┼─────────────┼─────────────┼─────────────┼──────────
Juin 2026       │  Juil.      │  Fin juil.  │  Août       │  Sept.
```

> ⚠️ Deadline compte gratuit GCP : fin juillet 2026 (90€/260€ consommés en mai)

---

## 🔗 Ressources officielles

- [CKAD — Linux Foundation](https://training.linuxfoundation.org/certification/certified-kubernetes-application-developer-ckad/)
- [CKA — Linux Foundation](https://training.linuxfoundation.org/certification/certified-kubernetes-administrator-cka/)
- [GCP ACE — Google Cloud](https://cloud.google.com/certification/cloud-engineer)
- [Killer.sh — simulateur CKAD/CKA](https://killer.sh/)
- [KodeKloud — CKAD course](https://kodekloud.com/courses/certified-kubernetes-application-developer-ckad/)
- [Google CloudSkillsBoost](https://www.cloudskillsboost.google/)
