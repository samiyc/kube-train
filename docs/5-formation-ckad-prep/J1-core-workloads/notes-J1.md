# Notes F5-J1 — Core workloads & vitesse kubectl

> Domaine CKAD : Application Design & Build (20 %) + compétence **transverse #1 : la vitesse**.
> Règle d'or : **ne jamais écrire un YAML from scratch.** Tout se génère à l'impératif, puis on édite.

---

## 0. Setup examen — à taper les yeux fermés (AVANT de lancer le chrono)

```bash
alias k=kubectl
export do="--dry-run=client -o yaml"      # génère le YAML sans rien créer
export now="--force --grace-period=0"      # suppression immédiate
source <(kubectl completion bash) && complete -o default -F __start_kubectl k
# ~/.vimrc : set tabstop=2 shiftwidth=2 expandtab   (indentation YAML sans galère)
```

> Au CKAD, ~15-20 tâches en 2h = **6-8 min/tâche**. La différence entre réussir et échouer,
> c'est la vitesse de génération du YAML, pas la connaissance des champs (open-book).

---

## 1. Impératif vs déclaratif — les 3 modes kubectl

| Mode | Commande type | Quand |
|------|---------------|-------|
| **Impératif pur** | `k run`, `k create`, `k scale`, `k delete` | Action ponctuelle, throwaway, ou point de départ |
| **Impératif → YAML** | `k create ... $do > f.yaml` puis éditer puis `k apply -f f.yaml` | **Le réflexe CKAD** : 90 % des tâches |
| **Déclaratif** | `k apply -f f.yaml` (fichier écrit/versionné) | État désiré géré dans le temps (GitOps, prod) |

**Le workflow gagnant** : `k create deploy web --image=nginx $do > web.yaml` → `vim web.yaml`
(ajouter ce que l'impératif ne sait pas faire) → `k apply -f web.yaml`.

`--dry-run=client` = génère localement, **rien n'est envoyé au cluster**.
`--dry-run=server` = envoie au serveur pour validation (admission, defaults) mais ne persiste pas.

---

## 2. Générer les workloads à la volée

```bash
# Pod
k run nginx --image=nginx $do
k run tmp --rm -it --image=busybox --restart=Never -- sh      # pod jetable de debug
k run p --image=busybox $do -- sleep 3600                      # command custom
k run p --image=nginx --env=FOO=bar --port=80 $do              # env + port inline

# Deployment
k create deployment web --image=nginx --replicas=3 $do

# Job / CronJob (détaillés en J3)
k create job hello --image=busybox $do -- echo hi
k create cronjob c --image=busybox --schedule="*/1 * * * *" $do -- date

# Exposer (crée un Service)
k expose deployment web --port=80 --target-port=8080 --name=web-svc $do
```

> Ce que l'impératif **ne sait pas** faire (→ générer puis éditer le YAML) : volumes,
> `resources` requests/limits, plusieurs containers, `securityContext`, probes, `initContainers`.

---

## 3. Modifier vite (sans réécrire)

```bash
k scale deployment web --replicas=5
k set image deployment/web nginx=nginx:1.27          # rolling update
k edit deployment web                                 # ouvre le YAML live dans $EDITOR
k label pod nginx tier=frontend                       # ajoute/retire (tier-) un label
k annotate pod nginx owner=sami
k patch deployment web -p '{"spec":{"replicas":4}}'   # patch ciblé (JSON merge)
k rollout restart deployment/web                      # relance les pods (recharge config)
```

---

## 4. Labels & selectors — le tissu conjonctif de K8s

```bash
k get pods -l tier=frontend                    # filtrer
k get pods -l 'env in (prod,staging)'          # ensembliste
k get pods -l 'tier=frontend,env!=dev'         # ET + négation
k get pods --show-labels
k delete pods -l tier=frontend                 # suppression en masse par label
```

**Point clé** : le `selector.matchLabels` d'un Deployment DOIT matcher les labels du
`template.metadata.labels`, sinon création refusée. Et un **Service** route vers les pods
dont les labels matchent son `selector` → c'est le même mécanisme qui relie Service → Endpoints → Pods.

---

## 5. `kubectl explain` — la doc dans le terminal

```bash
k explain pod.spec.containers                   # liste les champs + types
k explain deployment.spec.strategy --recursive  # arbre complet
k explain pod.spec.securityContext.runAsNonRoot # un champ précis
```

Réflexe open-book #1 : un champ oublié → `k explain` plutôt que d'ouvrir un navigateur. Plus rapide.

---

## 6. Cycle de vie & ownership

```
Deployment ──crée/gère──▶ ReplicaSet ──crée/gère──▶ Pod(s)
   (rollout, historique)     (nombre de replicas)      (exécution)
```

- Chaque objet enfant porte un `ownerReferences` vers son parent.
- Supprimer le **Deployment** → cascade (garbage collection) → RS + Pods supprimés.
- Supprimer un **Pod** géré → le RS en recrée un (l'état désiré = N replicas).
- `k delete pod x` = éphémère ; `k scale --replicas=0` = état désiré à 0 (persiste).

**STATUS courants à lire d'un coup d'œil** :
`ContainerCreating` · `Running` · `CrashLoopBackOff` (le container démarre puis crashe en boucle)
· `ImagePullBackOff` (image introuvable/non autorisée) · `Pending` (pas schedulable : ressources/selector)
· `Init:0/1` (init container en cours) · `Completed` (Job/pod terminé OK).

---

## 7. Révision transverse ↔ acquis kube-train

- Tu as déjà déployé kube-train en impératif+YAML (F1/F2) → ici on **accélère** et on couvre tous les cas.
- `k expose` / selectors ↔ le `Service` `kube-train-service` (selector `app: kube-train-pod`).
- ownerReferences ↔ le rolling update du `kube-train-deployment` observé en F4.

---

## Cheatsheet impératif → YAML (à garder sous les yeux au drill)

| Besoin | Commande |
|--------|----------|
| Pod simple | `k run nginx --image=nginx $do` |
| Pod + command | `k run p --image=busybox $do -- sleep 3600` |
| Deployment N replicas | `k create deploy web --image=nginx --replicas=3 $do` |
| Exposer | `k expose deploy web --port=80 --target-port=8080 $do` |
| Job | `k create job j --image=busybox $do -- echo hi` |
| CronJob | `k create cronjob c --image=busybox --schedule="*/5 * * * *" $do -- date` |
| ConfigMap | `k create cm conf --from-literal=k=v $do` |
| Secret | `k create secret generic s --from-literal=k=v $do` |
| Debug éphémère | `k run tmp --rm -it --image=busybox --restart=Never -- sh` |

---

## Après-midi — Drill chronométré (voir plan §J1)

5 blocs timés (pods multi-labels, deployment+expose+scale+rollback, command/env/resources,
filtrage/suppression par selector, pod de debug). Objectif : **tout en impératif, < 5-8 min/bloc.**

## Points clés à retenir
- (à compléter pendant le drill)

## Blocages rencontrés
- (à compléter)

## Cartes Anki créées
- (chaque commande hésitée → 1 carte)
