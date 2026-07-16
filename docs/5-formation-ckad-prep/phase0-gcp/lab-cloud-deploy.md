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

> ⚠️ **Surprise observée** : le rollback part lui aussi en `PENDING_APPROVAL` ! Voir §7.2.

> Le rollback **ne re-build rien** : il redéploie un artefact déjà rendu. D'où sa rapidité.
> C'est l'argument massue de Cloud Deploy vs un `kubectl apply` maison.

### 5.5 Voir ce qui tourne, sans jsonpath

```bash
# -L ajoute un label en colonne — bien plus lisible qu'un jsonpath à échapper
kubectl get pods -n lab-cloud-deploy-prod -L deploy.cloud.google.com/release-id
```
```
NAME                      READY   STATUS    AGE     RELEASE-ID
lab-app-6cc58d746-c6f8p   1/1     Running   6m59s   rel-001
```

> Pont livraison ↔ observabilité **gratuit** : en incident, tu sais en une commande quelle
> release tourne réellement en prod.

---

## 6. Où regarder dans la console GCP

> La console est **verbeuse et intimidante**. Voici les 4 seules pages qui comptent pour ce lab,
> et quoi y regarder.

### 🥇 La vue du pipeline — le tapis roulant en image

**[console.cloud.google.com/deploy/delivery-pipelines/europe-west1/lab-clouddeploy-pipeline](https://console.cloud.google.com/deploy/delivery-pipelines/europe-west1/lab-clouddeploy-pipeline?project=kube-train-project)**
*(ou `terraform output console_url` depuis `infra/labs/cloud-deploy/`)*

C'est **LA** page à garder ouverte. Le schéma du haut dit tout d'un coup d'œil :

| Élément | Ce que ça veut dire |
|---|---|
| Les 2 encadrés (staging / prod) | Les **targets**, avec la release actuellement dessus + son âge |
| La pastille centrale `0 en attente` / `1 en attente` | Les **approbations** en attente. Jaune + lien *Examiner* = une porte est fermée. |
| Lien **Promouvoir** sous staging | Fait avancer le gâteau au poste suivant |
| Bandeau **Déploiements / Fréquence / Taux d'échec** | Les **métriques DORA**, calculées nativement (voir §7.4) |
| Onglet **Versions** | Toutes les releases + l'état de leur dernier déploiement |
| Onglet **Automatisations** | Règles d'auto-promotion / auto-rollback (non utilisées ici, cf. §7.2) |

### Les 3 autres, ponctuellement

| Page | Quand |
|---|---|
| [**GKE › Charges de travail**](https://console.cloud.google.com/kubernetes/workload/overview?project=kube-train-project) — filtrer sur l'espace de noms `lab-cloud-deploy-*` | Voir `1/1` (staging) vs `2/2` (prod) côte à côte : la thèse du lab en une ligne |
| [**Cloud Build › Historique**](https://console.cloud.google.com/cloud-build/builds?project=kube-train-project) | Logs des jobs render/deploy quand un rollout échoue |
| [**Cloud Deploy › Cibles**](https://console.cloud.google.com/deploy/delivery-pipelines?project=kube-train-project) | Vérifier qu'un target a bien `require_approval` |

---

## 7. Résultats réels observés (16/07/2026)

### 7.1 Le rendu se fait à la CRÉATION de la release, pas à la promotion

```bash
gcloud deploy releases describe rel-001 --delivery-pipeline=$PIPELINE --region=$REGION \
  --format="yaml(targetRenders)"
```
```yaml
targetRenders:
  lab-clouddeploy-prod:     renderingBuild: .../builds/1e281d57-...   renderingState: SUCCEEDED
  lab-clouddeploy-staging:  renderingBuild: .../builds/8af20c8b-...   renderingState: SUCCEEDED
```

**Deux builds de rendu distincts, déjà `SUCCEEDED`** — alors que la promotion vers prod n'avait
pas encore eu lieu. Dès le `releases create`, Cloud Deploy rend les manifests **pour tous les
targets d'un coup**, et les **fige**.

C'est la clé qui explique tout le reste :
- **Promouvoir ne rend rien, ne build rien** → applique un YAML déjà figé → instantané.
- **Le rollback est instantané** pour la même raison : le rendu de rel-001 dort dans le bucket.
- **L'immuabilité est structurelle** : le manifest prod a été produit au même instant, depuis la
  même source, que celui de staging. Aucun `git push` intermédiaire ne peut polluer la prod.

### 7.2 Le rollback est soumis à la MÊME porte d'approbation

```
Creating rollout .../releases/rel-001/rollouts/rel-001-to-lab-clouddeploy-prod-0002
The rollout is pending approval.
```

Un rollback n'est pas une commande magique : c'est **un nouveau rollout** de rel-001 vers prod
(suffixe `-0002` = 2ᵉ rollout de cette release sur cette cible). Il hérite donc du
`require_approval` du target.

**Le compromis, et c'est une vraie question d'archi** :
- ✅ Personne ne revient en arrière en douce sur la prod — tracé, approuvé, auditable.
- ⚠️ À 3 h du matin en incident, ton rollback est **bloqué**. Si l'astreinte n'a pas le droit
  d'approuver, tu as fabriqué un MTTR catastrophique.

→ En vrai : donner le rôle d'approbateur à l'astreinte, ou utiliser les **automation rules**
(onglet *Automatisations*) pour auto-approuver les rollbacks.

### 7.3 Les labels injectés expliquent le rolling update à image identique

```
app.kubernetes.io/managed-by=google-cloud-deploy
deploy.cloud.google.com/release-id=rel-002        ← change entre rel-001 et rel-002
deploy.cloud.google.com/target-id=lab-clouddeploy-prod
skaffold.dev/run-id=17d5a41c305c4769ab1f719630639149
pod-template-hash=588f6dc8fb
```

rel-002 a été créée **sans modifier les manifests** — pourtant les pods ont été remplacés
(`6cc58d746` → `588f6dc8fb`). Cause : Cloud Deploy injecte `release-id` et `skaffold.dev/run-id`
**dans le pod template**. Le template change → nouveau `pod-template-hash` → nouveau ReplicaSet
→ rolling update, **à image strictement identique**.

Après rollback, le hash **revient à `6cc58d746`** et `release-id=rel-001`. Preuve visuelle nette.

### 7.4 Les métriques DORA sont natives

Le bandeau console affiche *Déploiements*, *Fréquence de déploiement*, *Taux d'échec* — **sans
aucune instrumentation**. Sujet direct de la certif GCP DevOps Engineer.

**Observé** : le compteur est passé de **2 → 3** après le rollback. **Un rollback compte comme un
déploiement** (conforme à DORA : *deployment frequency* mesure les mises en prod, pas les
nouveautés). Corollaire : un rollback n'améliore pas le *change failure rate*, il le dégrade —
d'où la nécessité de lire les 4 métriques **ensemble** (fréquence haute + échec bas = maturité ;
fréquence haute + rollbacks fréquents = fuite en avant).

---

## 8. Ce que ça prouve — ✅ validé le 16/07/2026

- [x] Release déployée **automatiquement** sur le 1er stage → `rel-001-to-...-staging-0001 SUCCEEDED`, 1 pod
- [x] Promotion **bloquée** en `PENDING_APPROVAL` → et le namespace `lab-cloud-deploy-prod` **n'existait même pas** : la porte bloque l'application de *tous* les manifests, Namespace compris
- [x] Après approbation : **2 replicas** en prod vs **1** en staging, même release → *même artefact, config différente*
- [x] **Rollback** vers rel-001 sans rebuild → hash `6cc58d746` + `release-id=rel-001` revenus
- [x] `serial_pipeline` impose l'ordre → impossible de sauter staging
- [x] Baseline `default` **jamais polluée** : 3 pods / 4 containers pendant tout le lab

---

## 9. Coût observé

| Ressource | Coût réel |
|---|---|
| Cloud Deploy (pipeline, targets, releases) | **Gratuit** — facturation aux jobs d'exécution seulement |
| Cloud Build (2 rendus/release + 1 deploy/rollout) | ~1-2 min par job → **dans le free tier** (120 min/jour) |
| Pods du lab (1 + 2 nginx, ~150m CPU) | Marginal sur Autopilot, quelques centimes/heure |
| Bucket d'artefacts `*_clouddeploy` | Quelques Ko → **≈ 0 €** |
| **Total lab** | **≈ 0 €** — le cluster, lui, tournait déjà |

---

## 10. Blocages rencontrés — et comment on les a corrigés

> Le vrai apprentissage est souvent là. Chaque blocage ci-dessous a été vécu pendant ce lab.

### 🪤 B1 — `Service account service-<num>@gcp-sa-clouddeploy... does not exist`

**Symptôme** : 1er `terraform apply` → **11/12 ressources créées**, échec sur le binding IAM.

**Cause** : activer une API ne crée pas son *service agent* immédiatement (provisioning
**paresseux**). Mon `depends_on` garantissait l'**ordre**, pas l'**existence** — et l'email de
l'agent était une **chaîne construite à la main**, donc *aucune dépendance* dans le graphe.

**Mauvais fix** : relancer `terraform apply` (ça passe… et ça réintroduit une étape manuelle sur
tout projet neuf).
**Bon fix** : `google_project_service_identity` (provider `google-beta`) crée l'agent, et le
binding référence son `.email` → dépendance réelle, déterministe. Détail complet en §4.

> 🎓 **Leçon généralisable** : en Terraform, `depends_on` **ordonne** ; une **référence
> d'attribut** garantit l'existence. Une chaîne construite ne crée aucun lien.

### 🪤 B2 — `Failed to find attribute [region]` sur les commandes gcloud deploy

**Symptôme** : `gcloud deploy releases promote` → *The [release] resource is not properly
specified. Failed to find attribute [region]*.

**Cause** : rien à voir avec Cloud Deploy — les variables `$PIPELINE` / `$REGION` n'étaient pas
exportées dans ce shell (le `releases create` avait été lancé avec les valeurs **en dur**).
`--region=` arrivait donc **vide**.

**Fix** :
```bash
export REGION=europe-west1
export PIPELINE=lab-clouddeploy-pipeline
# ou, mieux, une fois pour toutes :
gcloud config set deploy/region europe-west1
```
> L'erreur le suggérait elle-même (`set the property deploy/region`) — **lire les messages
> d'erreur en entier**, ils contiennent souvent le remède.

### 🪤 B3 — La console dit `rel-001`, kubectl dit `rel-002`

**Symptôme** : après l'`approve` du rollback, la console affiche prod = rel-001 « À l'instant »,
mais `kubectl get pods` montre encore les pods rel-002 (AGE 24m).

**Cause** : **timing**. `gcloud deploy rollouts approve` rend la main *immédiatement* — il ne fait
que lever la barrière. Le job de déploiement (Cloud Build) démarre ensuite et prend ~30-60 s.

**Fix** : attendre et re-vérifier. Ce n'était pas une incohérence mais deux photos à des instants
différents.
> 🎓 En asynchrone, « la commande a rendu la main » ≠ « l'effet est visible ». Toujours vérifier
> l'**état final** (`rollouts list`, ou le label `release-id` sur les pods).

### 🪤 B4 — jsonpath vide sur un label à points et slash

**Symptôme** : `-o jsonpath='{.items[*].metadata.labels.deploy\.cloud\.google\.com/release-id}'`
→ sortie **vide**, sans erreur.

**Cause** : l'échappement d'une clé contenant à la fois des `.` et un `/` est piégeux.

**Fix** — utiliser `-L`, bien plus lisible :
```bash
kubectl get pods -n lab-cloud-deploy-prod -L deploy.cloud.google.com/release-id
```

---

## 11. Cleanup — retour à la baseline

```bash
# 1. Les workloads déployés par le pipeline ne sont PAS dans le state Terraform
#    (créés par les rollouts) → le namespace dédié est le seul filet fiable.
kubectl delete namespace lab-cloud-deploy-staging lab-cloud-deploy-prod --ignore-not-found

# 2. Le pipeline, les targets, le SA
cd /mnt/c/DEVDIR/GITHUB/kube-train/infra/labs/cloud-deploy
terraform destroy

# 3. Les DEUX buckets que Cloud Deploy s'est AUTO-créés (hors state Terraform !)
#    a) les SOURCES envoyées par --source=. → gs://<uid-du-pipeline>_clouddeploy
#       (le hash EST l'uid du delivery pipeline, visible dans terraform destroy)
#    b) les RENDUS figés → gs://<region>.deploy-artifacts.<project>.appspot.com
#       (valeur lisible dans execution_configs.artifact_storage du target)
gcloud storage ls | grep -E "clouddeploy|deploy-artifacts"
# gcloud storage rm -r gs://<uid>_clouddeploy
# gcloud storage rm -r gs://europe-west1.deploy-artifacts.kube-train-project.appspot.com
# → optionnel : quelques Ko, ~0 €. À garder si tu comptes rejouer le lab.

# 4. Vérifier le retour à la baseline : 3 pods / 4 containers, rien de plus
kubectl get pods -o='custom-columns=NAME:.metadata.name,CONTAINERS:.spec.containers[*].name'
```

> Les APIs (`clouddeploy`, `cloudbuild`) restent activées (`disable_on_destroy = false`) —
> gratuit et sans effet de bord.

> 🎓 **Le pattern à retenir** : plusieurs choses échappent au state Terraform et doivent être
> nettoyées à la main — les **pods déployés par les rollouts** (d'où le namespace dédié), les
> **deux buckets auto-créés** (sources + rendus), et les **releases/rollouts** eux-mêmes
> (supprimés avec le pipeline). Un service managé qui crée ses propres ressources est un angle
> mort classique de l'IaC : Terraform ne gère que ce qu'il a créé.

---

## 12. À retenir (matière à QCM)

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
- **Le rendu a lieu à la création de la release**, pour **tous les targets d'un coup**, et il est
  **figé**. C'est *la* raison pour laquelle promote et rollback sont instantanés et sûrs.
- **Le rollback est un rollout comme un autre** → soumis à `require_approval`. Compromis
  auditabilité vs MTTR : à arbitrer via les rôles d'astreinte ou les automation rules.
- **Cloud Deploy injecte des labels** (`release-id`, `target-id`, `skaffold.dev/run-id`) dans le
  pod template → rolling update même à image identique, et pont livraison↔observabilité gratuit.
- **DORA natif** : fréquence de déploiement + taux d'échec sans instrumentation. **Un rollback
  compte comme un déploiement** et dégrade le change failure rate — les 4 métriques se lisent
  ensemble.
- **Angle mort de l'IaC** : un service managé crée ses propres ressources (pods des rollouts,
  bucket d'artefacts) → hors state Terraform → cleanup manuel (d'où le namespace dédié).
