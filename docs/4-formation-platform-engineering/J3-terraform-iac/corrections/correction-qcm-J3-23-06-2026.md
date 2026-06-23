# Correction QCM J3 — Terraform & IaC GCP
> Date : 23/06/2026 | Score : **6 / 8**

---

## Question 1 — Terraform lifecycle → **1 / 1** ✅

**Réponse : B** — `init → plan → apply → destroy`

| Commande | Rôle |
|---|---|
| `terraform init` | Télécharge le provider GCP, initialise le backend GCS |
| `terraform plan` | Diff state ↔ HCL ↔ infra réelle — aucune modification |
| `terraform apply` | Applique le plan sur l'infra réelle |
| `terraform destroy` | Supprime toutes les ressources du state |

**Ordre de A** incorrect : on ne peut pas `apply` avant `init` (le provider n'est pas téléchargé).  
**Ordre de C** incorrect : `plan` avant `init` est impossible pour la même raison.

---

## Question 2 — State file, backend GCS, locking → **1 / 1** ✅

**Réponse : C**

Le state est la **mémoire de Terraform** : il mappe chaque `resource` HCL à son identifiant réel dans le cloud.

| Option | Erreur |
|---|---|
| A | Le state n'est pas régénérable sans perte — supprimer le state = Terraform ne reconnaît plus l'infra existante |
| B | Les fichiers `.tf` restent la source de vérité déclarative — le state décrit l'existant, pas l'intention |
| **C ✅** | Correct : mémoire + correspondance HCL↔réel + backend GCS = centralisation + locking |
| D | `fmt` et `validate` n'ont pas besoin du state |

**Point clé** : le backend GCS ajoute deux bénéfices critiques — verrouillage (empêche deux `apply` simultanés) et versioning (rollback si state corrompu).

---

## Question 3 — terraform import → **1 / 1** ✅

**Réponse : C**

`terraform import` fait **uniquement** ceci : il ajoute la ressource dans le state Terraform avec son ID réel GCP. Il ne génère pas le HCL.

```
Avant import : Terraform ignore le cluster existant → plan proposerait de le créer
Après import : Terraform connaît le cluster → plan compare HCL ↔ réalité
```

Le workflow complet après import :
1. `terraform state show google_container_cluster.main` — voir les attributs importés
2. Recopier les attributs dans le HCL
3. Itérer jusqu'à `terraform plan` → `No changes`

---

## Question 4 — provider vs resource vs data vs module → **0 / 1** ❌

**Ta réponse : C — Correcte : A**

| Option | Analyse |
|---|---|
| **A ✅** | `provider` = plugin (hashicorp/google), `resource` = objet créé/géré, `data` = objet lu sans cycle de vie, `module` = regroupement réutilisable |
| B | Tout faux — provider ≠ bucket GCS |
| **C ❌ (ta réponse)** | Mélange concepts GCP et Terraform : `provider` ≠ compte de service, `resource` ≠ projet GCP, `data` ≠ état Terraform, `module` ≠ plugin compilé |
| D | Tout faux — provider ≠ pipeline CI |

**Piège de C** : les termes (compte de service, projet GCP, état Terraform) sont des concepts GCP/Terraform réels, mais mal associés aux blocs HCL. C est conçu pour piéger si les définitions ne sont pas précisément mémorisées.

**Mémo rapide** :

| Bloc | Ce que c'est | Exemple |
|---|---|---|
| `provider` | Plugin qui sait parler à un cloud | `provider "google"` |
| `resource` | Objet créé ET géré par Terraform | `google_container_cluster.main` |
| `data` | Objet **lu** dans l'infra (pas géré) | `data "google_compute_network" "default"` |
| `variable` | Entrée externe (CLI, `.tfvars`, CI) | `var.project_id` |
| `locals` | Calcul interne dérivé | `local.app_prefix` |
| `output` | Valeur exposée après apply | `output "cluster_endpoint"` |
| `module` | Groupement réutilisable de ressources | `module "gke"` |

---

## Question 5 — Variables, locals, outputs → **1 / 1** ✅

**Réponse : C**

```hcl
# variable → entrée externe
variable "project_id" { type = string }

# locals → calcul interne (jamais d'entrée externe)
locals {
  full_name = "${var.project_id}-kube-train"
}

# output → expose vers l'extérieur (CI, autre module)
output "cluster_endpoint" {
  value = google_container_cluster.main.endpoint
}
```

| Option | Erreur |
|---|---|
| A | Mettre des IDs sensibles dans `locals` ne les cache pas — le state les stocke en clair |
| B | `output` et `locals` ont leurs rôles inversés |
| **C ✅** | Correct — séparation claire des responsabilités |
| D | `variables` et `locals` ont des rôles distincts et non redondants |

---

## Question 6 — Workload Identity Federation via Terraform → **0 / 1** ❌

**Tu as bloqué — Correcte : B**

```hcl
resource "google_service_account_iam_member" "api_workload_identity" {
  service_account_id = google_service_account.kube_train_api.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:kube-train-project.svc.id.goog[default/kube-train-api-sa]"
}
```

**Pourquoi B et pas les autres :**

| Option | Problème |
|---|---|
| A | `roles/container.admin` = droits d'administration cluster GKE, sans rapport avec WIF |
| **B ✅** | `google_service_account_iam_member` + `workloadIdentityUser` + membre au format `{project}.svc.id.goog[{namespace}/{ksa}]` |
| C | `storage.objectAdmin` = droits sur un bucket GCS, sans rapport |
| D | `pubsub.subscriber` = droits Pub/Sub, sans rapport |

**Les deux ressources IAM à distinguer** :

| Ressource Terraform | S'applique sur | Exemple d'usage |
|---|---|---|
| `google_project_iam_member` | Le projet GCP entier | `roles/cloudsql.client` pour l'API |
| `google_service_account_iam_member` | Un GSA spécifique | `roles/iam.workloadIdentityUser` pour le binding KSA→GSA |

**Format du membre WIF** :
```
serviceAccount:{project_id}.svc.id.goog[{namespace}/{ksa_name}]
```

- `{project_id}` = `kube-train-project`
- `{namespace}` = namespace K8s **exact** où tourne le pod (`default` pour kube-train)
- `{ksa_name}` = nom du K8s ServiceAccount

> ⚠️ Piège rencontré en TP : utiliser `kube-train` au lieu de `default` comme namespace → token WIF rejeté silencieusement → `CrashLoopBackOff` avec `Connection reset at doAuthentication` (SQLState `08001`). Le fix ne nécessite pas de `terraform apply` — un `kubectl annotate --overwrite` + `rollout restart` suffit.

---

## Question 7 — Lecture d'un terraform plan → **1 / 1** ✅

**Réponse : D**

| Symbole | Signification | Risque |
|---|---|---|
| `+` | Création d'une nouvelle ressource | Faible |
| `~` | Modification **in-place** d'une ressource existante | Moyen |
| `-` | Destruction d'une ressource | **Élevé** |
| `-/+` | Remplacement forcé (destroy + create) — certains attributs sont immuables | **Très élevé** |
| `<=` | Lecture d'une `data source` | Nul |

**Différence critique `~` vs `-/+`** :
- `~` = GCP peut modifier l'attribut sans recréer la ressource (ex: labels, tags)
- `-/+` = l'attribut est immuable → GCP doit détruire et recréer (ex: changer la région d'un cluster GKE → destruction complète du cluster)

**Toujours lire le plan avant `apply`** — particulièrement les `-/+` qui peuvent détruire des données.

---

## Question 8 — GitOps pour l'infra → **1 / 1** ✅

**Réponse : D**

Le workflow GitOps Terraform optimal :

```
Feature branch
  → PR (modifie infra/)
  → terraform plan (CI, commentaire sticky sur la PR)
  → Review humaine du diff infra
  → Merge main
  → terraform apply (CI, Workload Identity Federation, backend GCS partagé)
```

| Option | Problème |
|---|---|
| A | `apply` depuis un laptop → non auditable, pas de backend partagé, état potentiellement divergent |
| B | `destroy` sur PR → destructif, bloque tout le workflow |
| C | `apply` sur chaque branch feature → corruptions de state si plusieurs branches simultanées |
| **D ✅** | PR → plan → review → merge → apply : auditabilité, sécurité, reproductibilité |

**Pourquoi Workload Identity et pas clé JSON** :
- Clé JSON = secret statique, longue durée de vie, risque de fuite
- WIF = token éphémère (~1h), révoqué automatiquement, aucun secret dans GitHub

---

## Synthèse

| Question | Thème | Résultat |
|---|---|---|
| 1 | Lifecycle Terraform | ✅ |
| 2 | State + backend GCS | ✅ |
| 3 | terraform import | ✅ |
| 4 | Blocs HCL fondamentaux | ❌ C→A |
| 5 | Variables / locals / outputs | ✅ |
| 6 | Workload Identity Federation | ❌ Bloqué→B |
| 7 | Lecture terraform plan | ✅ |
| 8 | GitOps Terraform | ✅ |

**Score final : 6 / 8**

### Points à consolider

**Q4 — Blocs HCL** : la confusion vient du mélange entre terminologie GCP (compte de service, projet) et blocs Terraform (provider, resource, data). Mémorise le tableau : `provider` = plugin, `resource` = géré, `data` = lu, `module` = réutilisable.

**Q6 — WIF member format** : le format `serviceAccount:{project}.svc.id.goog[{namespace}/{ksa}]` est la syntaxe clé de Workload Identity. Le namespace doit correspondre exactement au namespace K8s du pod — c'est le piège n°1 rencontré en TP (namespace `kube-train` vs `default`). À mémoriser absolument.
