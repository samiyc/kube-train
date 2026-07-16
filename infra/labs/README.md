# infra/labs — labs GCP jetables (Phase 0 F5)

> Chaque lab est un **root module Terraform indépendant**, avec son **propre state**.
> Objectif : pouvoir tout détruire et **revenir exactement à la baseline**, sans jamais
> toucher à `infra/` ni à `infra/bootstrap/`.

---

## 🔒 Les 3 niveaux d'isolation

| State | Prefix GCS | Contenu | Cycle de vie |
|---|---|---|---|
| `infra/` | `platform-engineering/dev` | cluster, Cloud SQL, Pub/Sub, registry, SA applicatifs, APIs | destroy/apply quotidien |
| `infra/bootstrap/` | `platform-engineering/bootstrap` | SA GitHub, WIF, secrets | **jamais** détruit |
| `infra/labs/<lab>/` | `platform-engineering/labs/<lab>` | ressources du lab uniquement | **jetable à volonté** |

**Pourquoi `terraform apply` dans `infra/` ne déploie PAS les labs** : Terraform ne lit que les
`.tf` du **répertoire courant** (root module) — il ne descend jamais dans les sous-dossiers, sauf
si on les référence explicitement via un bloc `module { source = "./..." }`. `infra/` n'en contient
aucun. Les labs sont donc totalement invisibles pour lui, exactement comme `bootstrap/` l'est déjà.

---

## 📐 Convention pour ajouter un lab

```
infra/labs/<nom-du-lab>/
├── versions.tf     backend gcs, prefix = "platform-engineering/labs/<nom-du-lab>"
├── providers.tf    provider google
├── variables.tf    project_id, region (defaults identiques à infra/)
├── apis.tf         APIs propres au lab, disable_on_destroy = false
├── data.tf         data sources vers l'existant (cluster, registry…) — PAS de remote_state
└── main.tf         les ressources du lab
```

**Règles :**

1. **Lecture de l'existant par `data`, jamais par `terraform_remote_state`.**
   Un lab lit le cluster/registry **par son nom** → couplage lâche. Si `infra/` est détruit puis
   recréé, le lab n'a rien à savoir. (Un `remote_state` créerait une dépendance de state à state.)

2. **APIs du lab dans le lab**, en `disable_on_destroy = false`.
   `infra/apis.tf` reste réservé au socle. Détruire un lab ne désactive jamais son API (inoffensif, gratuit).

3. **Workloads K8s d'un lab → namespace dédié `lab-<nom>`.**
   Le nettoyage devient `kubectl delete ns lab-<nom>` et la baseline du namespace `default`
   n'est jamais polluée. Important : ce qu'un pipeline (ex. Cloud Deploy) déploie **n'est pas
   dans le state Terraform** → le namespace dédié est le seul filet fiable.

4. **Aucun lab ne modifie `infra/` ni `bootstrap/`.** Si un lab a besoin d'une ressource durable,
   c'est qu'elle n'appartient pas à un lab → elle remonte dans `infra/`.

---

## 🧹 Détruire un lab (retour à la baseline)

```bash
cd infra/labs/<nom-du-lab>
terraform destroy

# Si le lab a déployé des workloads K8s :
kubectl delete namespace lab-<nom-du-lab> --ignore-not-found
```

**Vérifier le retour à la baseline** (l'état stable de référence) :

```bash
kubectl get pods -o='custom-columns=NAME:.metadata.name,CONTAINERS:.spec.containers[*].name'
```

Attendu — **3 pods / 4 containers**, rien de plus :

```
kube-train-deployment-xxxxx     api-container,cloud-sql-proxy
notification-deployment-xxxxx   notification-container
otel-collector-xxxxx            otel-collector
```

> Si un pod supplémentaire subsiste, c'est qu'un lab a déployé hors de son namespace dédié → à corriger.

---

## 📎 Voir aussi

- Index Phase 0 & ordre des labs : `../../docs/5-formation-ckad-prep/phase0-gcp/readme.md`
- Notes par lab : `../../docs/5-formation-ckad-prep/phase0-gcp/lab-*.md`
- Cleanup budget global : `cd infra && terraform destroy` + stop Cloud SQL
