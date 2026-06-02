# Tips GCP — Budget & Cloud Logging

> Astuces pratiques apprises pendant les formations F2-F3 pour optimiser les coûts et le debugging.

---

## 💰 Budget GCP — Leçons apprises

### Consommation observée (mai 2026)

| Configuration | Coût/jour | Détail |
|---------------|-----------|--------|
| GKE Autopilot (API + notif + OTel) | ~4-5€ | 2-3 pods applicatifs |
| GKE + ArgoCD (3 pods supplémentaires) | ~8-10€ | ArgoCD server + repo-server + app-controller |
| GKE + Istio (sidecars Envoy) | ~6-8€ estimé | +30% CPU/mem par pod mesh |
| Cloud SQL db-f1-micro (actif 24/7) | ~0.25€/jour | Arrêter quand non utilisé |

### Stratégies d'économie

```bash
# 1. Supprimer le cluster en fin de journée
gcloud container clusters delete kube-train-cluster \
  --region europe-west1 --project kube-train-project --quiet

# 2. Recréer le matin (F4: via Terraform, sinon manuellement)
gcloud container clusters create-auto kube-train-cluster \
  --region europe-west1 --project kube-train-project

# 3. Arrêter Cloud SQL entre les sessions
gcloud sql instances patch kube-train-db \
  --activation-policy=NEVER --project kube-train-project

# 4. Réactiver Cloud SQL
gcloud sql instances patch kube-train-db \
  --activation-policy=ALWAYS --project kube-train-project

# 5. Supprimer ArgoCD quand pas utilisé
kubectl delete namespace argocd
```

### Rappel crédits (mai 2026)

- Crédits initiaux : 260,15€
- Consommés au 30/05 : ~128€ (29 jours)
- Restant : ~132€
- Objectif F4 : ≤ 5€/jour → ~26 jours de marge

### Postes les plus chers (à surveiller)

1. **GKE Autopilot vCPU** (~0.10€/vCPU/h) — principal poste
2. **Cloud SQL** (~7€/mois si actif 24/7) — arrêter entre sessions
3. **LoadBalancer IP** (frais réseau) — supprimé avec le cluster
4. **ArgoCD / Istio** — pods supplémentaires qui consomment du vCPU

---

## 🔍 Cloud Logging — Requêtes utiles

### Requête de base (erreurs API)

```
resource.type="k8s_container"
resource.labels.container_name="api-container"
severity="ERROR"
```

### Exclure les faux-positifs (OTel + JVM warnings)

```
resource.type="k8s_container"
resource.labels.container_name="api-container"
severity="ERROR"
-textPayload=~"OpenJDK 64-Bit Server VM warning"
-textPayload=~"otel.javaagent"
```

**Syntaxe LQL :**
- `-` = exclusion
- `=~` = regex match
- `=` = exact match
- `!=` = not equal

### Autres requêtes utiles

```
# Logs applicatifs (pas infra) de l'API
resource.type="k8s_container"
resource.labels.container_name="api-container"
jsonPayload.log.logger=~"com.kubetrain"

# Erreurs du OTel Collector
resource.type="k8s_container"
resource.labels.container_name="otel-collector"
severity="ERROR"

# Logs de la notification service
resource.type="k8s_container"
resource.labels.container_name="notification-container"

# Pods en CrashLoopBackOff (événements K8s)
resource.type="k8s_event"
jsonPayload.reason="BackOff"

# Startup lent (probe failures)
resource.type="k8s_event"
jsonPayload.reason="Unhealthy"
resource.labels.pod_name=~"kube-train"
```

### Sauvegarder une requête

Dans l'explorateur de journaux → **Bibliothèque de requêtes** → **Enregistrer la requête** :
- Nom : "API Errors (sans faux-positifs)"
- Permet de la réutiliser en 1 clic

---

## 🛠️ CI/CD — Notes pratiques

### La pipeline ne recrée PAS le cluster

Le `deploy.yml` vérifie si le cluster existe (`gcloud container clusters get-credentials`). Si le cluster est supprimé :
- Le job `deploy` skip gracefully
- Les jobs `test` et `build` fonctionnent toujours (pas besoin du cluster)
- Pour redéployer : recréer le cluster puis re-push (ou relancer la pipeline)

### SonarCloud — Piège "Automatic Analysis"

Si l'erreur `"You are running CI analysis while Automatic Analysis is enabled"` apparaît :
- Aller dans SonarCloud → Settings → Analysis Method
- **Désactiver "Automatic Analysis"** (laisser la CI piloter)

### Trivy — Faux positifs

Créer `.trivyignore` à la racine du service concerné :
```
# CVE non applicable (pas d'usage direct de la lib vulnérable)
CVE-2026-XXXXX
```

---

*Dernière mise à jour : 2026-05-30*
