# Lab 1 — Cloud Deploy : le tapis roulant de livraison

> **Phase 0 (GCP-first)** · Cluster requis ✅ · Effort moyen (~1 demi-journée)
> Terraform : `infra/labs/cloud-deploy/` — Exploitation : `gcloud deploy` (ci-dessous)

---

## 1. ELI5 — c'est quoi Cloud Deploy ?

> **Imagine une usine de gâteaux.**
>
> Tu as cuit **un** gâteau (ton image Docker). Tu veux le faire goûter d'abord à l'équipe
> (*staging*), et s'il est bon, le servir aux clients (*prod*).
>
> **Ce que tu NE veux pas** : recuire un deuxième gâteau pour les clients. Sinon, celui qu'ils
> mangent n'est pas celui qui a été goûté et validé. C'est LE bug classique de la mise en prod.
>
> **Cloud Deploy est le tapis roulant.** Tu poses **le** gâteau dessus une seule fois (une
> *release*). Le tapis l'amène d'abord à l'équipe, puis — si tu appuies sur le bouton
> (*promotion*) — au comptoir client. Le gâteau ne change **jamais** en route : seule la
> **présentation** change (assiette en carton en interne, porcelaine pour les clients).
>
> Et le tapis a une **barrière avant le comptoir** : un humain doit dire « ok, envoie »
> (*approbation*). Si les clients se plaignent, on remet l'ancien gâteau en 30 secondes
> (*rollback*) — il est toujours au frigo.

**En une phrase** : Cloud Deploy garantit que **l'artefact promu en prod est exactement celui
qui a été testé en staging**, avec des portes d'approbation et un rollback instantané.

---

## 2. Du gâteau au vrai vocabulaire

| ELI5 | Terme GCP | Dans ce lab |
|---|---|---|
| Le tapis roulant | **Delivery pipeline** | `lab-clouddeploy-pipeline` |
| Les postes du tapis (équipe, comptoir) | **Targets** | `lab-clouddeploy-staging`, `lab-clouddeploy-prod` |
| Le gâteau posé sur le tapis | **Release** (immuable) | `rel-001`, `rel-002`… |
| Le gâteau à un poste donné | **Rollout** | un par target et par release |
| Appuyer sur le bouton pour avancer | **Promotion** | `gcloud deploy releases promote` |
| La barrière avant le comptoir | **require_approval** | activée sur le target `prod` |
| L'assiette (carton vs porcelaine) | **Profil skaffold** | `staging` (1 replica) / `prod` (2 replicas) |
| Le cuisinier qui manipule | **SA d'exécution** | `lab-clouddeploy-sa` |

> ⚠️ **Le contre-sens à éviter** : Cloud Deploy **ne build pas**. Il ne fabrique pas le gâteau.
> Le build reste chez toi (GitHub Actions → Artifact Registry). Cloud Deploy prend un artefact
> **déjà construit** et gère son **voyage** vers les environnements. C'est du **CD**, pas du CI.

---

## 3. Ce qu'on construit

```
     TOI                    CLOUD DEPLOY (le tapis)                      GKE
  ┌────────┐        ┌──────────────────────────────────┐
  │release │──────▶ │  stage 1 : staging                │──▶ ns lab-cloud-deploy-staging
  │ (image)│        │    profil skaffold "staging"       │      1 replica
  └────────┘        │            │                       │
                    │            ▼  promote (bouton)     │
                    │  stage 2 : prod                    │──▶ ns lab-cloud-deploy-prod
                    │    ⛔ require_approval              │      2 replicas
                    │    profil skaffold "prod"          │
                    └──────────────────────────────────┘
```

**Choix budget assumé** : les 2 targets pointent sur le **même cluster**, dans 2 **namespaces**
différents. En vrai on aurait 2 clusters — le concept de promotion est rigoureusement identique,
et ça nous coûte 0 € de plus.

---

## 4. Provisionner (Terraform)

```bash
cd /mnt/c/DEVDIR/GITHUB/kube-train/infra/labs/cloud-deploy
terraform init
terraform plan     # attendu : ~10 to add, 0 to change, 0 to destroy
terraform apply
```

> **Rappel isolation** : ce lab a son propre state (`platform-engineering/labs/cloud-deploy`).
> `terraform apply` dans `infra/` ne le voit pas. `terraform destroy` ici ne casse rien d'autre.

Ce que ça crée : les 2 APIs (`clouddeploy`, `cloudbuild`), le SA d'exécution + ses rôles,
le service agent + son binding d'impersonation, les 2 targets, le pipeline.

**Rien n'est encore déployé sur le cluster** — le tapis est monté, mais vide.

### 🪤 Piège vécu : le service agent qui n'existe pas encore

Premier `apply` → 11 ressources créées, 1 échec :

```
Error 400: Service account service-399291708401@gcp-sa-clouddeploy.iam.gserviceaccount.com
           does not exist., badRequest
```

**Cause** : activer une API ne crée pas son *service agent* immédiatement — GCP le provisionne
**paresseusement**. Un `depends_on` sur `google_project_service` garantit l'**ordre**, pas
l'**existence**. Et comme l'email de l'agent était construit à la main
(`service-${data.google_project.this.number}@gcp-sa-clouddeploy...`), Terraform n'avait **aucune
dépendance réelle** vers l'objet : il fonçait.

**Le mauvais fix** : relancer `terraform apply` (ça passe, l'agent a eu le temps d'apparaître).
Ça marche… et ça réintroduit une étape manuelle à chaque projet neuf. Exactement la dette qu'on
élimine depuis F4.

**Le bon fix** : faire créer l'agent **par une ressource**, et référencer *sa sortie* :

```hcl
resource "google_project_service_identity" "clouddeploy" {
  provider   = google-beta          # ressource beta-only
  service    = "clouddeploy.googleapis.com"
  depends_on = [google_project_service.clouddeploy]
}

resource "google_service_account_iam_member" "agent_impersonates_exec_sa" {
  member = "serviceAccount:${google_project_service_identity.clouddeploy.email}"
  # ...
}
```

> 🎓 **La leçon, généralisable** : en Terraform, une **chaîne construite à la main** ne crée
> aucune dépendance dans le graphe. Référencer l'**attribut d'une ressource** est ce qui force
> l'ordre *et* l'existence. `depends_on` ordonne ; une référence garantit.

---

## 5. Exploiter (gcloud) — le cœur du lab

Terraform a monté le tapis. Maintenant on y pose des gâteaux.

```bash
cd /mnt/c/DEVDIR/GITHUB/kube-train/infra/labs/cloud-deploy/app
REGION=europe-west1
PIPELINE=lab-clouddeploy-pipeline
```

### 5.1 Créer une release → déploiement AUTO en staging

```bash
gcloud deploy releases create rel-001 \
  --delivery-pipeline=$PIPELINE \
  --region=$REGION \
  --source=.
```

> `--source=.` envoie le dossier (skaffold.yaml + manifests) à Cloud Deploy, qui le rend et le
> déploie **automatiquement sur le premier stage**. Pas besoin de promouvoir vers staging.

Vérifier :
```bash
gcloud deploy releases list --delivery-pipeline=$PIPELINE --region=$REGION
kubectl get pods -n lab-cloud-deploy-staging     # → 1 pod lab-app
```

### 5.2 Promouvoir vers prod → bloqué par l'approbation

```bash
gcloud deploy releases promote --release=rel-001 \
  --delivery-pipeline=$PIPELINE --region=$REGION
```

Le rollout part en **PENDING_APPROVAL** — la barrière fait son travail :
```bash
gcloud deploy rollouts list --release=rel-001 \
  --delivery-pipeline=$PIPELINE --region=$REGION
# → state: PENDING_APPROVAL
kubectl get pods -n lab-cloud-deploy-prod        # → RIEN. Normal : pas encore approuvé.
```

### 5.3 Approuver → la prod se déploie

```bash
ROLLOUT=$(gcloud deploy rollouts list --release=rel-001 \
  --delivery-pipeline=$PIPELINE --region=$REGION \
  --filter="state=PENDING_APPROVAL" --format="value(name.basename())")

gcloud deploy rollouts approve $ROLLOUT \
  --release=rel-001 --delivery-pipeline=$PIPELINE --region=$REGION

kubectl get pods -n lab-cloud-deploy-prod        # → 2 pods (profil prod !)
```

**Observe** : même release, **2 replicas** en prod contre 1 en staging. L'artefact est identique,
seul le profil skaffold diffère. C'est *exactement* le point du lab.

### 5.4 Rollback

```bash
# Poser un 2e gâteau (change l'image en 1.28 dans app/*/deployment.yaml avant, si tu veux voir un vrai diff)
gcloud deploy releases create rel-002 \
  --delivery-pipeline=$PIPELINE --region=$REGION --source=.
gcloud deploy releases promote --release=rel-002 --delivery-pipeline=$PIPELINE --region=$REGION
# ... approuver ...

# Puis revenir à la release précédente sur prod :
gcloud deploy targets rollback lab-clouddeploy-prod \
  --delivery-pipeline=$PIPELINE --region=$REGION --release=rel-001
```

> Le rollback **ne re-build rien** : il redéploie un artefact déjà rendu. D'où sa rapidité.
> C'est l'argument massue de Cloud Deploy vs un `kubectl apply` maison.

### 5.5 La vue console (le tapis en image)

```bash
terraform output console_url    # depuis infra/labs/cloud-deploy/
```

---

## 6. Ce que ça prouve

- [ ] Une release déployée **automatiquement** sur le 1er stage (staging)
- [ ] Une promotion **bloquée** en `PENDING_APPROVAL` (la porte prod fonctionne)
- [ ] Après approbation : **2 replicas** en prod vs 1 en staging → *même artefact, config différente*
- [ ] Un **rollback** vers `rel-001` sans rebuild
- [ ] Impossible de sauter staging → le `serial_pipeline` impose l'ordre

---

## 7. Coût observé

| Ressource | Coût |
|---|---|
| Cloud Deploy (pipeline, targets) | **Gratuit** (facturation aux jobs d'exécution) |
| Cloud Build (jobs render/deploy) | ~1-2 min/rollout — largement dans le free tier |
| Pods du lab (1 + 2 nginx, ~150m CPU total) | Marginal sur Autopilot |
| **Total lab** | **≈ 0 €** (le cluster, lui, tourne déjà) |

→ *(à confirmer après le run — noter ici le réel)*

---

## 8. Cleanup — retour à la baseline

```bash
# 1. Les workloads déployés par le pipeline ne sont PAS dans le state Terraform
#    (créés par les rollouts) → le namespace dédié est le seul filet fiable.
kubectl delete namespace lab-cloud-deploy-staging lab-cloud-deploy-prod --ignore-not-found

# 2. Le pipeline, les targets, le SA
cd /mnt/c/DEVDIR/GITHUB/kube-train/infra/labs/cloud-deploy
terraform destroy

# 3. Le bucket d'artefacts que Cloud Deploy s'est AUTO-créé (hors state Terraform !)
#    Repérable à son suffixe _clouddeploy. Contient les sources et les rendus.
gcloud storage ls | grep clouddeploy
gcloud storage rm -r gs://<hash>_clouddeploy      # optionnel : quelques Ko, ~0 €

# 4. Vérifier le retour à la baseline : 3 pods / 4 containers, rien de plus
kubectl get pods -o='custom-columns=NAME:.metadata.name,CONTAINERS:.spec.containers[*].name'
```

> Les APIs (`clouddeploy`, `cloudbuild`) restent activées (`disable_on_destroy = false`) —
> gratuit et sans effet de bord.

> 🎓 **Le pattern à retenir** : trois choses échappent au state Terraform et doivent être
> nettoyées à la main — les **pods déployés par les rollouts** (d'où le namespace dédié), le
> **bucket d'artefacts auto-créé** par Cloud Deploy, et les **releases/rollouts** eux-mêmes
> (supprimés avec le pipeline). Un service managé qui crée ses propres ressources est un angle
> mort classique de l'IaC : Terraform ne gère que ce qu'il a créé.

---

## 9. À retenir (matière à QCM)

- **CD ≠ CI** : Cloud Deploy ne build pas, il **promeut un artefact déjà construit**.
- **Release = immuable** : c'est la garantie que prod == ce qui a été testé.
- **Rollout** = une release **sur un target donné** (1 release → N rollouts).
- **`serial_pipeline`** impose l'ordre des stages : on ne saute pas staging.
- **`require_approval`** = porte humaine, matérialisée par un rollout `PENDING_APPROVAL`.
- **Profils skaffold** = ce qui différencie les environnements sans toucher à l'artefact.
- **SA d'exécution** : Cloud Deploy tourne avec une identité dédiée, et son **service agent**
  doit avoir `iam.serviceAccountUser` dessus pour l'endosser.
- **Rollback** = redéploiement d'un rendu existant → pas de rebuild → quasi instantané.
- **Terraform** : `depends_on` ordonne, une **référence d'attribut** garantit l'existence.
  Un email de service agent construit en chaîne = aucune dépendance = race condition.
- **Service agent ≠ SA d'exécution** : l'agent est l'identité *du service GCP* (créée par
  Google, `gcp-sa-*`) ; le SA d'exécution est *la tienne*, celle que l'agent endosse pour agir.
