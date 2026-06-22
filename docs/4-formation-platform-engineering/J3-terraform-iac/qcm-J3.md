# QCM J3 — Terraform & IaC GCP

**8 questions — Durée estimée : 10-15 min**

---

## Question 1 — [Terraform lifecycle]
Dans un workflow Terraform classique sur `infra/` pour kube-train, quel enchaînement correspond au cycle de vie normal d'une infra déjà décrite en HCL ?

A) `terraform apply` → `terraform init` → `terraform plan` → `terraform destroy`
B) `terraform init` → `terraform plan` → `terraform apply` → `terraform destroy`
C) `terraform plan` → `terraform init` → `terraform apply` → `terraform validate`
D) `terraform init` → `terraform apply` → `terraform refresh` → `terraform output`

---

## Question 2 — [State file, backend GCS, locking]
Quel énoncé décrit le mieux le rôle du state Terraform quand kube-train utilise un backend GCS ?

A) Il contient uniquement les variables et peut être régénéré sans impact.
B) Il remplace les fichiers `.tf` et devient la source de vérité métier.
C) Il mémorise la correspondance entre les ressources réelles et la config Terraform ; avec GCS on centralise le state et on évite les écritures concurrentes via le mécanisme de verrouillage du backend.
D) Il sert uniquement à accélérer `terraform fmt` et `terraform validate`.

---

## Question 3 — [terraform import]
Que fait réellement `terraform import` lorsqu'on rattache le cluster `kube-train-cluster` à `google_container_cluster.main` ?

A) Il génère automatiquement tout le HCL exact du cluster et du VPC.
B) Il supprime la ressource existante puis la recrée pour la rendre compatible.
C) Il ajoute la ressource dans le state Terraform, mais il ne produit pas à lui seul une configuration HCL propre et complète.
D) Il crée un module Terraform prêt à être publié dans le Registry.

---

## Question 4 — [provider vs resource vs data vs module]
Dans le contexte kube-train, quelle association est correcte ?

A) `provider` = plugin d'accès à GCP, `resource` = objet créé/géré, `data` = objet lu sans gestion de cycle de vie, `module` = regroupement réutilisable de ressources.
B) `provider` = bucket GCS, `resource` = output, `data` = variable, `module` = backend.
C) `provider` = compte de service, `resource` = projet GCP, `data` = état Terraform, `module` = plugin compilé.
D) `provider` = pipeline GitHub Actions, `resource` = job CI, `data` = secret GitHub, `module` = environnement GKE.

---

## Question 5 — [Variables, locals, outputs]
Quelle pratique est correcte pour structurer le code Terraform de kube-train ?

A) Mettre les IDs sensibles directement dans les `locals` pour éviter les variables.
B) Utiliser `output` pour calculer des noms internes et `locals` pour exposer des valeurs au pipeline.
C) Utiliser `variable` pour les entrées externes (project ID, region), `locals` pour les dérivations internes (préfixes de nommage) et `output` pour exposer des valeurs utiles (URL, emails de service accounts, nom du bucket).
D) Utiliser uniquement `outputs`, car `variables` et `locals` sont redondants.

---

## Question 6 — [Workload Identity Federation via Terraform]
Pour autoriser le ServiceAccount Kubernetes `kube-train-api-sa` du namespace `default` à agir comme le GSA `kube-train-api-sa@kube-train-project.iam.gserviceaccount.com`, quelle ressource Terraform est la plus adaptée ?

> ⚠️ Note : kube-train déploie dans le namespace `default` (pas `kube-train`). Le namespace dans le membre WIF doit correspondre **exactement** au namespace K8s du pod — une erreur ici est silencieuse et entraîne un `Connection reset` au démarrage.

A) `google_project_iam_binding` avec `roles/container.admin`
B) `google_service_account_iam_member` avec le rôle `roles/iam.workloadIdentityUser` et le membre `serviceAccount:kube-train-project.svc.id.goog[default/kube-train-api-sa]`
C) `google_storage_bucket_iam_member` avec le rôle `roles/storage.objectAdmin`
D) `google_pubsub_subscription_iam_member` avec le rôle `roles/pubsub.subscriber`

---

## Question 7 — [Lecture d'un terraform plan]
Dans la sortie de `terraform plan`, que signifient les symboles `+`, `~` et `-` ?

A) `+` erreur de provider, `~` ressource importée, `-` backend non initialisé
B) `+` création, `~` remplacement forcé, `-` drift détecté sans suppression
C) `+` création locale uniquement, `~` update du state, `-` archive dans GCS
D) `+` création d'une ressource, `~` modification sur une ressource existante, `-` destruction d'une ressource

---

## Question 8 — [GitOps pour l'infra]
Quel workflow GitOps est le plus sain pour l'infrastructure Terraform de kube-train ?

A) Un développeur pousse sur `main`, exécute `terraform apply` depuis son laptop, puis colle un screenshot dans le PR après coup.
B) Le workflow GitHub Actions exécute `terraform destroy` à chaque PR pour vérifier que le code compile.
C) On lance `terraform apply` directement sur chaque branche feature pour aller plus vite, puis on corrige le state à la main.
D) Une PR déclenche `terraform plan`, le résultat est publié en commentaire, la revue valide le diff infra, puis le merge sur `main` déclenche `terraform apply` avec identité fédérée et backend distant partagé.

