# Lab 3 — Cloud Profiler : le diagnostic moteur, branché en permanence

> **Phase 0 (GCP-first)** · Cluster requis ✅ · Effort faible (~1-2 h)
> Terraform : `infra/labs/profiler/` — App : `kube-train-api/Dockerfile` + `k8s/workloads/deployment-gke.yaml`

> ⚠️ **La moitié « Trace » de ce lab est déjà acquise.** La trace distribuée E2E
> api→notification (propagation via l'Outbox, `reservation.id`, SA dédié) a été livrée en F4 :
> `../../4-formation-platform-engineering/extra/trace-e2e-outbox-propagation.md`.
> Ce lab porte donc sur **Cloud Profiler**, le complément manquant.

---

## 1. ELI5 — Trace vs Profiler

> **Trace = le GPS du camion de livraison.**
> Tu suis **un** colis : parti de l'entrepôt à 14h02, 3 min d'arrêt au dépôt, 8 min chez le client.
> Tu sais **où** le temps est passé, **entre** les étapes. Mais si un arrêt dure 8 min, le GPS ne
> te dit pas *pourquoi*.
>
> **Profiler = le diagnostic moteur, branché en permanence.**
> Il ne suit aucun colis en particulier. Il échantillonne le moteur en continu et te dit :
> *« 40 % du carburant est brûlé par ce piston »*. Traduit : *« 40 % du CPU est brûlé par cette
> méthode Java »*.
>
> Les deux sont complémentaires : **Trace** te dit *quel span* est lent → **Profiler** te dit
> *quelle ligne de code* dans ce span.

| | Trace | Profiler |
|---|---|---|
| Unité observée | **une requête** | **agrégat continu** de tout le trafic |
| Question | *où* le temps passe, entre services | *quel code* brûle CPU / mémoire |
| Portée | **largeur** (inter-services) | **profondeur** (dans ton code) |
| Déclenchement | par requête (échantillonnée) | permanent, ~0 impact (échantillonnage statistique) |
| Sur kube-train | `POST /reservations` → outbox → notification | quelles méthodes de `kube-train-api` coûtent |

---

## 2. Vocabulaire

| ELI5 | Terme GCP | Ici |
|---|---|---|
| Le diagnostic branché au moteur | **Agent Profiler** (natif, in-process) | `/opt/cprof/profiler_java_agent.so` |
| Le nom du moteur ausculté | `-cprof_service` | `kube-train-api` |
| La version du moteur | `-cprof_service_version` | `1.0.0` — permet de **comparer 2 versions** |
| Le rapport de combustion | **Flame graph** | Console Profiler |
| Type de carburant mesuré | **Profile type** | CPU time · Heap · Wall time |

**Lire un flame graph** : chaque barre = une méthode ; sa **largeur = le temps qu'elle consomme**
(pas sa durée d'exécution — sa part du total). On lit **de haut en bas** pour la pile d'appels, et
on cherche **les barres larges** : ce sont elles qui coûtent. Une barre fine, même profonde, est
sans intérêt.

---

## 3. Particularité : ce lab touche l'app

Contrairement au lab Cloud Deploy (100 % isolé), **Profiler exige un agent in-process** : on ne
peut pas profiler une app depuis l'extérieur. L'isolation change donc de nature :

| Couche | Quoi | Mécanisme d'isolation |
|---|---|---|
| **Infra** | API `cloudprofiler` + rôle `cloudprofiler.agent` sur le GSA existant | **State Terraform jetable** (`labs/profiler`) |
| **App** | Agent embarqué (Dockerfile) + activé (`JAVA_TOOL_OPTIONS`) | **git** (revert du commit) |

> 🎯 Le binding IAM est **additif** (`google_project_iam_member`) : le lab ajoute un rôle à un SA
> qu'il **ne possède pas** (`kube-train-api-sa` appartient à `infra/`). Le `destroy` retire
> uniquement ce rôle — les autres (`cloudsql.client`, `pubsub.publisher`…) ne bougent pas.

**Design de l'activation** — calqué sur l'agent OTel existant :
- L'agent est **embarqué au build** (Dockerfile, un `curl` comme pour OTel)
- Il n'est **activé que par `-agentpath`** dans `JAVA_TOOL_OPTIONS`
- → **retirer la variable d'env le désactive, sans rebuild**

C'est la même philosophie que le commentaire du Dockerfile : *« Pour désactiver sans reconstruire :
`OTEL_TRACES_EXPORTER=none` »*.

---

## 4. Provisionner (Terraform)

```bash
cd /mnt/c/DEVDIR/GITHUB/kube-train/infra/labs/profiler
terraform init
terraform plan     # attendu : 2 to add (API + binding IAM), 0 to destroy
terraform apply
```

---

## 5. Activer l'agent (app) + déployer

Les modifs sont déjà committées :

**`kube-train-api/Dockerfile`** (build stage) :
```dockerfile
RUN curl -L --fail -o /tmp/profiler_java_agent.tar.gz \
      https://storage.googleapis.com/cloud-profiler/java/latest/profiler_java_agent.tar.gz \
 && mkdir -p /opt/cprof \
 && tar -xzf /tmp/profiler_java_agent.tar.gz -C /opt/cprof \
 && rm /tmp/profiler_java_agent.tar.gz
```
*(archive vérifiée : contient `profiler_java_agent.so` à la racine)*

**`k8s/workloads/deployment-gke.yaml`** :
```yaml
- name: JAVA_TOOL_OPTIONS
  value: "-Djava.io.tmpdir=/tmp -agentpath:/opt/cprof/profiler_java_agent.so=-cprof_service=kube-train-api,-cprof_service_version=1.0.0,-logtostderr"
```

Le déploiement passe par la CI (rebuild de l'image nécessaire, ~6 min) :
```bash
git push          # → test → build → deploy
kubectl rollout status deployment/kube-train-deployment
```

**Vérifier que l'agent a démarré** (grâce à `-logtostderr`) :
```bash
kubectl logs deployment/kube-train-deployment -c api-container | grep -i "profiler\|cprof"
# → attendu : "Cloud Profiler ... started" / "Successfully collected profile"
```

> `readOnlyRootFilesystem: true` (F4-J1) ne gêne pas : l'agent **lit** `/opt/cprof` et écrit
> dans `/tmp` (emptyDir), déjà pointé par `-Djava.io.tmpdir=/tmp`.

---

## 6. Où regarder dans la console

**[console.cloud.google.com/profiler](https://console.cloud.google.com/profiler/kube-train-api/cpu?project=kube-train-project)**
*(ou `terraform output console_url`)*

| Élément | Ce que ça veut dire |
|---|---|
| Sélecteur **Service** | `kube-train-api` (= ton `-cprof_service`) |
| Sélecteur **Profile type** | **CPU time** (le plus parlant) · **Heap** (allocations) · **Wall time** (temps réel, I/O inclus) |
| Le **flame graph** | Largeur = part du temps consommé. Cherche les **barres larges**. |
| **Compare to** | Comparer deux `service_version` ou deux périodes → *voir une régression de perf* |
| **Weight / Filter** | Isoler un package (ex. `com.kubetrain`) pour masquer le bruit JVM/Spring |

> ⏱️ **Patience** : le premier profil met **~5-10 min** à apparaître (l'agent échantillonne par
> tranches puis pousse). Ne conclus pas trop vite que ça ne marche pas.

---

## 7. L'expérience — que chercher dans TON code

Génère du trafic pour que le profil soit représentatif :
```bash
LB=$(kubectl get svc kube-train-service -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
for i in $(seq 1 300); do curl -s http://$LB/trains > /dev/null; done
for i in $(seq 1 50); do
  curl -s -X POST http://$LB/reservations -H "Content-Type: application/json" \
    -d '{"passengerName":"Profiler Test","trainId":"TGV-7042"}' > /dev/null
done
```

**Hypothèses à vérifier dans le flame graph** (c'est ça, l'intérêt de profiler du vrai code) :

1. **L'`OutboxPoller` tourne toutes les 5 s, même sans trafic.** Apparaît-il dans le profil CPU ?
   À quel coût ? C'est un `@Scheduled` + requête SQL en boucle → candidat idéal.
2. **Hibernate / Jackson** : la sérialisation JSON et le mapping ORM sont les suspects habituels
   d'une API REST. Quelle part réelle ?
3. **L'agent OTel lui-même** : l'instrumentation a un coût. Visible ?
4. **Heap** : que génère `/trains` en allocations ?

> 🎓 L'intérêt n'est pas d'optimiser (le service est trivial), mais d'apprendre à **lire** un
> profil et à confronter tes intuitions au réel. En mission, c'est exactement ce raisonnement qui
> transforme « l'app est lente » en « cette méthode coûte 30 % ».

---

## 8. Ce que ça prouve

- [ ] L'agent démarre et pousse des profils (logs + console)
- [ ] Un flame graph **CPU time** de `kube-train-api` s'affiche
- [ ] On identifie au moins **une méthode `com.kubetrain.*`** dans le profil
- [ ] On répond à l'hypothèse : **l'OutboxPoller coûte-t-il quelque chose ?**
- [ ] Le profil **Heap** est disponible
- [ ] La baseline reste saine : 3 pods / 4 containers, app fonctionnelle

---

## 9. Coût

| Ressource | Coût |
|---|---|
| **Cloud Profiler** | **Gratuit** — aucune facturation, quel que soit le volume |
| Surcoût CPU de l'agent | ~négligeable (échantillonnage statistique, conçu pour la prod) |
| **Total lab** | **0 €** |

→ *(à confirmer après le run)*

---

## 10. Blocages rencontrés

*(à remplir pendant le lab)*

---

## 11. Cleanup — retour à la baseline

Ce lab a **deux** niveaux à défaire :

```bash
# 1. INFRA — API + binding IAM (le SA lui-même appartient à infra/, il ne bouge pas)
cd /mnt/c/DEVDIR/GITHUB/kube-train/infra/labs/profiler
terraform destroy

# 2. APP — retirer l'agent (revert du commit du lab)
cd /mnt/c/DEVDIR/GITHUB/kube-train
git revert <sha-du-commit-lab>      # ou retirer à la main -agentpath + le bloc Dockerfile
git push                             # → CI rebuild + redeploy

# 3. Vérifier la baseline
kubectl get pods -o='custom-columns=NAME:.metadata.name,CONTAINERS:.spec.containers[*].name'
kubectl logs deployment/kube-train-deployment -c api-container | grep -ci cprof   # → 0
```

> 💡 **Désactivation express, sans rebuild** : retirer seulement `-agentpath...` de
> `JAVA_TOOL_OPTIONS` puis `kubectl apply -f k8s/workloads/deployment-gke.yaml`. L'agent reste
> dans l'image mais n'est plus chargé. C'est tout l'intérêt d'avoir séparé *embarquer* et *activer*.

> L'API `cloudprofiler` reste activée (`disable_on_destroy = false`) — gratuit, sans effet de bord.

---

## 12. À retenir (matière à QCM)

- **Trace ≠ Profiler** : Trace = *où* le temps passe entre services (une requête) ; Profiler =
  *quel code* brûle CPU/mémoire (agrégat continu). Complémentaires, pas concurrents.
- **Profiler exige un agent in-process** → impossible de profiler « de l'extérieur ». C'est ce qui
  l'empêche d'être un lab 100 % isolé.
- **Flame graph** : la **largeur** = la part du temps consommé. Chercher les barres **larges**,
  pas les profondes.
- **Embarquer ≠ activer** : agent dans l'image (build) + activé par variable d'env (runtime)
  → on désactive sans rebuild. Pattern réutilisable (c'est celui de l'agent OTel).
- **`google_project_iam_member` est additif** → un lab peut ajouter un rôle à un SA qu'il ne
  possède pas, et le retirer au destroy sans toucher au reste.
- **Auth** : l'agent utilise **Workload Identity / ADC** — même mécanisme que Cloud SQL Proxy,
  Pub/Sub et l'OTel Collector. Un seul modèle d'identité pour tout GCP.
- **Cloud Profiler est gratuit** et conçu pour tourner **en production continue** — contrairement
  à un profiler de dev (JProfiler, VisualVM) qu'on branche ponctuellement.
