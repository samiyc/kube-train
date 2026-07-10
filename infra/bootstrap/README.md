# infra/bootstrap — couche identité & secrets

> ⛔ **Ne jamais lancer `terraform destroy` ici.**

## Pourquoi un state séparé ?

`infra/` est détruit et recréé quotidiennement pour tenir le budget (≤ 5 €/jour).
Certaines ressources ne doivent **jamais** disparaître dans ce cycle :

| Ressource | Conséquence d'une suppression |
|---|---|
| Pool Workload Identity `github-pool` | **Soft-delete 30 jours** — impossible de recréer un pool du même nom → la CI ne peut plus s'authentifier pendant un mois |
| `github-actions-sa` + ses rôles | La CI ne peut plus push d'image ni déployer |
| Secrets `api-key`, `db-username`, `db-password` | **Perte des valeurs** |

Un state distinct (`prefix = platform-engineering/bootstrap`) garantit qu'un
`terraform destroy` dans `infra/` ne peut pas les atteindre. Les ressources les
plus irrécupérables portent en plus `prevent_destroy = true`.

## Contenu

- `github-actions-sa` + rôles `artifactregistry.writer`, `container.admin`, `secretmanager.secretAccessor`
- Pool + provider Workload Identity Federation (auth GitHub Actions sans clé JSON)
- Binding restreignant l'usurpation du SA au seul dépôt `samiyc/kube-train`
- Conteneurs Secret Manager (**valeurs jamais dans Terraform**)

## Adoption de l'existant (à faire UNE fois)

Ces ressources existent déjà (créées à la main). Il faut les **importer**, sinon
`apply` échouerait en `409 AlreadyExists`.

### Avant tout : comparer le code à la réalité

Le code doit refléter l'existant **avant** l'apply, sinon Terraform « corrigerait »
une config de sécurité en service.

```bash
PROJECT=kube-train-project

# Provider OIDC — vérifié le 2026-07-10 :
#   attributeCondition: assertion.repository=='samiyc/kube-train' && assertion.ref=='refs/heads/main'
#   attributeMapping:   google.subject, attribute.repository
gcloud iam workload-identity-pools providers describe github-provider \
  --location=global --workload-identity-pool=github-pool \
  --project=$PROJECT --format=yaml

# Pool — comparer displayName / description
gcloud iam workload-identity-pools describe github-pool \
  --location=global --project=$PROJECT --format=yaml

# Binding sur le SA — vérifier le principalSet exact
gcloud iam service-accounts get-iam-policy \
  github-actions-sa@$PROJECT.iam.gserviceaccount.com \
  --project=$PROJECT --format=yaml
```

> ⚠️ `attribute_condition` restreint le dépôt **et la branche**. Le relâcher
> laisserait n'importe quelle branche (donc n'importe quelle PR) déployer en prod.

```bash
cd /mnt/c/DEVDIR/GITHUB/kube-train/infra/bootstrap
terraform init

PROJECT=kube-train-project

terraform import google_service_account.github_actions \
  "projects/$PROJECT/serviceAccounts/github-actions-sa@$PROJECT.iam.gserviceaccount.com"

terraform import google_iam_workload_identity_pool.github \
  "projects/$PROJECT/locations/global/workloadIdentityPools/github-pool"

terraform import google_iam_workload_identity_pool_provider.github \
  "projects/$PROJECT/locations/global/workloadIdentityPools/github-pool/providers/github-provider"

for s in api-key db-username db-password; do
  terraform import "google_secret_manager_secret.app[\"$s\"]" "projects/$PROJECT/secrets/$s"
done
```

Les `google_project_iam_member` et `google_service_account_iam_member` sont
**additifs et idempotents** : pas besoin de les importer, `apply` les adoptera
sans rien changer côté GCP.

```bash
terraform plan    # attendu : quasi 0 changement (au pire des libellés)
terraform apply
```

## Migration depuis `infra/` (à faire UNE fois, avant le premier apply de bootstrap)

Les deux bindings GitHub vivaient dans `infra/iam.tf`. Les retirer du state
`infra/` **sans les supprimer sur GCP** :

```bash
cd /mnt/c/DEVDIR/GITHUB/kube-train/infra
terraform state rm google_project_iam_member.github_actions_container_admin
terraform state rm google_project_iam_member.github_actions_secret_accessor
```

`terraform state rm` retire du state uniquement — la ressource GCP reste en place.

## Nouveau projet GCP (après la fin de l'essai)

Ordre obligatoire :

```bash
# 1. Créer le bucket de state (poule/œuf, reste manuel)
gcloud storage buckets create gs://kube-train-terraform-state \
  --project=$PROJECT --location=europe-west1 --uniform-bucket-level-access
gcloud storage buckets update gs://kube-train-terraform-state --versioning

# 2. Bootstrap (identité + secrets) — pas d'import, tout est neuf
cd infra/bootstrap && terraform init && terraform apply

# 3. Renseigner les valeurs des secrets (hors Terraform)
printf '%s' '<api-key>'      | gcloud secrets versions add api-key     --data-file=- --project=$PROJECT
printf '%s' '<db-username>'  | gcloud secrets versions add db-username --data-file=- --project=$PROJECT
printf '%s' '<db-password>'  | gcloud secrets versions add db-password --data-file=- --project=$PROJECT

# 4. Infra applicative
cd .. && terraform init && terraform apply

# 5. Mettre à jour le numéro de projet dans .github/workflows/deploy.yml
#    (workload_identity_provider: projects/<NUMÉRO>/locations/global/...)
```

> ⚠️ Le `workload_identity_provider` du workflow contient le **numéro de projet**
> (`399291708401`) en dur. Sur un projet neuf, il faut le remplacer.
