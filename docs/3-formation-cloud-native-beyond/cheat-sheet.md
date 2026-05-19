# 🛠️ Commande de configuration K8S ou GCP via GCLOUD

#### F3-J1: Flyway, Outbox Pattern & Spring Cloud Contract
```
Pas de commandes a lancer
```

#### F3-J2: OpenTelemetry & Observabilité distribuée
```
# Activer Cloud Trace API
gcloud services enable cloudtrace.googleapis.com --project=kube-train-project

# Vérifier le rôle (compute SA = déjà utilisé par Cloud SQL Proxy)
gcloud projects add-iam-policy-binding kube-train-project \
  --member="serviceAccount:399291708401-compute@developer.gserviceaccount.com" \
  --role="roles/cloudtrace.agent"
```

#### F3-J3: [Titre]
```
a compléter
```

#### F3-J4: [Titre]
```
a compléter
```

#### F3-J5: [Titre]
```
a compléter
```
