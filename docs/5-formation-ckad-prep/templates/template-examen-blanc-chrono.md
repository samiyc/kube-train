# Template — Examen blanc CKAD chronométré (reproductible)

> Copier sous `examen-blanc-N.md`. Objectif : reproduire les conditions réelles du CKAD.
> **2h · ~16 tâches · open-book `kubernetes.io/docs` uniquement · ≥ 66 % pour passer.**

---

## ⏱️ Règles

- Chrono strict de 2h (timer visible).
- Seul onglet autorisé : `kubernetes.io/docs` (+ `kubernetes.io/blog`, `helm.sh/docs`).
- Alias `k`, `$do`, complétion autorisés (les configurer AVANT de lancer le chrono).
- Chaque tâche indique son **contexte** (namespace/cluster) — toujours vérifier `kubectl config current-context` avant.
- Ne pas rester bloqué : une tâche dure ~6-8 min max, sinon `# flag` et passer à la suivante.

---

## 🛠️ Setup de l'environnement (avant le chrono)

```bash
# Créer les namespaces jetables de l'examen
for ns in ckad-mockN-core ckad-mockN-config ckad-mockN-net ckad-mockN-obs; do
  kubectl create namespace "$ns"
done
# Pré-déployer les ressources "cassées" que certaines tâches doivent réparer
# (à définir par examen — voir section Tâches)
```

## 🔄 Procédure de RESET (pour rejouer l'examen)

```bash
# Supprime tout l'environnement de l'examen → rejouable à l'identique
for ns in ckad-mockN-core ckad-mockN-config ckad-mockN-net ckad-mockN-obs; do
  kubectl delete namespace "$ns" --wait=false
done
# Puis relancer le Setup ci-dessus
```

---

## 📋 Tâches (barème temps indicatif)

> Répartition cible alignée sur les poids CKAD : Config&Security 25 %, Design/Deploy/Networking 20 % chacun, Observability 15 %.

| # | Domaine | Tâche (résumé) | Poids | Temps cible | Fait |
|---|---------|----------------|-------|-------------|------|
| 1 | Core/Design | (ex. créer un deployment impératif + scaler) | 4 % | 5 min | ☐ |
| 2 | Config&Sec | (ex. ConfigMap → volume) | 4 % | 6 min | ☐ |
| 3 | Config&Sec | (ex. SA + Role + RoleBinding + can-i) | 7 % | 8 min | ☐ |
| 4 | Config&Sec | (ex. securityContext non-root + read-only FS) | 7 % | 7 min | ☐ |
| 5 | Design | (ex. init container attente service) | 5 % | 7 min | ☐ |
| 6 | Design | (ex. sidecar emptyDir partagé) | 5 % | 8 min | ☐ |
| 7 | Design | (ex. Job completions/parallelism) | 5 % | 6 min | ☐ |
| 8 | Design | (ex. CronJob concurrencyPolicy) | 5 % | 6 min | ☐ |
| 9 | Deploy | (ex. rollout undo vers révision N) | 5 % | 6 min | ☐ |
| 10 | Deploy | (ex. Kustomize overlay patch replicas) | 8 % | 9 min | ☐ |
| 11 | Deploy | (ex. Helm install + --set) | 5 % | 6 min | ☐ |
| 12 | Networking | (ex. expose ClusterIP + NodePort) | 5 % | 6 min | ☐ |
| 13 | Networking | (ex. Ingress 2 paths) | 8 % | 9 min | ☐ |
| 14 | Networking | (ex. NetworkPolicy default-deny + allow) | 7 % | 8 min | ☐ |
| 15 | Observability | (ex. ajouter 3 probes) | 7 % | 7 min | ☐ |
| 16 | Observability | (ex. diagnostiquer CrashLoopBackOff) | 8 % | 8 min | ☐ |

**Total** : ~100 % · ~2h

---

## ✅ Correction & score

| Domaine | Points possibles | Points obtenus |
|---------|------------------|----------------|
| Application Design & Build (20 %) | | |
| Config & Security (25 %) | | |
| Application Deployment (20 %) | | |
| Services & Networking (20 %) | | |
| Observability & Maintenance (15 %) | | |
| **TOTAL** | **100 %** | **__ %** (seuil 66 %) |

**Lacunes identifiées** :
- (à remplir)

**Cartes Anki créées** :
- (une par tâche ratée : recto = énoncé, verso = séquence de commandes)

---

*Rejouer via la procédure de RESET jusqu'à ≥ 80 % en < 1h45 avant de programmer l'examen réel.*
