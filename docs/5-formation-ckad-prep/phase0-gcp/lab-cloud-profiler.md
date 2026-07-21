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

- [x] L'agent démarre et pousse des profils (logs `Creating a new profile` + console)
- [x] Un flame graph **CPU time** de `kube-train-api` s'affiche
- [x] On identifie au moins **une méthode `com.kubetrain.*`** dans le profil → `OutboxPoller.processPendingEvents`
- [x] On répond à l'hypothèse : **l'OutboxPoller coûte-t-il quelque chose ?** → oui, ~1,5-2,1 % CPU, #1 du code métier (cf. § 9)
- [ ] Le profil **Heap** est disponible *(reste à faire — hypothèse n°4)*
- [x] La baseline reste saine : app fonctionnelle (2000/2000 POST → 201)

> **⏳ Reste à faire (demain, inter-mission)** — 5 tests, ~7 min, 0 €, par ROI décroissant :
> 1. **Profil Heap** (Type de profil → *Tas*) → dernier `[ ]` ci-dessus. Qui alloue sur `/trains` + les 2000 events ? (Hibernate + Jackson attendus)
> 2. **Resserrer la fenêtre à ~5 min** (CPU) → prouver que l'arbre protobuf `<clinit>` **fond** (confirme l'écart n°2 one-shot).
> 3. **Filtre `createReservation`** (CPU) → confirmer visuellement que le POST est minuscule (preuve du pattern Outbox).
> 4. **Wall filtré `OutboxPoller`** → voir le `future.get(10s)` d'attente Pub/Sub (le vrai coût du poller = attente I/O, pas CPU).
> 5. **« Comparer à » → période précédente** → exercer la détection de régression **sans rebuild** (2 fenêtres). ⚠️ Ne PAS bumper `service_version` (rebuild 6 min inutile).
>
> Puis : passer le statut du lab en ✅ dans [`readme.md`](readme.md) (ligne 36, 🚧 → ✅).

---

## 9. Résultats réels & écarts vs attendu (run E2E — 21/07)

**Charge injectée** : 2000 `POST /reservations` en **4 boucles parallèles** (`( … ) & … wait`),
codes HTTP comptés → **2000/2000 → `201`** (`sort | uniq -c`).

### Ce qu'on a mesuré (Temps CPU, filtre `kubetrain`)

`OutboxPoller.processPendingEvents` = **la barre `com.kubetrain.*` la plus large** :
**17-21 ms (1,5-2,1 %)** selon la fenêtre. Hypothèse n°1 tranchée : **oui, le poller coûte
quelque chose, et c'est le premier poste de ton code.**

### Écart n°1 — le POST ne domine PAS (c'est le pattern Outbox)

Attendu : voir `createReservation` grossir avec la charge. Réel : il est **quasi absent**.

Le code l'explique — `POST /reservations` écrit juste une ligne dans `outbox_events` (cheap) et
rend la main. Tout le coût (désérialiser le payload, re-sérialiser pour Pub/Sub, publier, logger)
est porté par `OutboxPoller` **en tâche de fond**. **Le profiler prouve visuellement la
distribution de coût du pattern Outbox** : le chemin requête reste pauvre, le poller encaisse.

### Décomposition réelle du coût `OutboxPoller` (confirmée dans le code)

| Branche flame graph | Ligne de code | Nature |
|---|---|---|
| `ObjectMapper.readValue → BeanDeserializer` | `objectMapper.readValue(payload, ReservationEvent.class)` (`processEvent`) | Jackson **désérialise** l'event stocké — hypothèse n°2 ✓, steady-state |
| `Publisher.publish → JsonWriter` | `objectMapper.writeValueAsString(event)` (`publish`) | Jackson **re-sérialise** pour Pub/Sub |
| `Logger.info → callAppenders → JsonWriter` | **2× `log.info` par event** (`processEvent` + `publish`), en JSON structuré | 2000 events → **~4000 sérialisations de logs** |
| `findByStatusOrderByCreatedAtAsc → Hibernate → PgPreparedStatement` | la requête de poll + le `save()` UPDATE | JDBC Postgres |
| `PubsubMessage.Builder + DescriptorProtos.<clinit>` | `PubsubMessage.newBuilder()…` | protobuf (voir écart n°2) |

> 💡 Poste inattendu : **le logging**. Deux `log.info` JSON par event publié = un coût CPU réel et
> évitable (passer en `debug`, ou logger par batch). En mission, c'est typiquement le « coût
> gratuit » qu'un profil révèle.

### Écart n°2 — le piège `<clinit>` (coût one-shot)

L'arbre `com.google.protobuf.DescriptorProtos.<clinit>` / `PubsubProto.<clinit>` reste visible même
au 2ᵉ run. Ce sont des **initialisations de classe = une seule fois** (1er publish). Elles ne
fondent **pas** ici car la **fenêtre est restée à 30 min** → elle englobe encore le 1er publish.
Leçon : **resserrer la Période** pour isoler le steady-state (le `<clinit>` disparaît alors).

### Écart n°3 — Wall time ≠ CPU (et le cold-start à ~38 s)

En basculant Type de profil sur **Durée d'exécution (Wall)**, c'est **le bootstrap Spring**
(`SpringApplication.run → preInstantiateSingletons → …`) qui écrase tout — soit les **~38 s de
cold-start** qui justifient le `startupProbe` (cf. CLAUDE.md). Deux raisons : (1) un pod frais
(`…-rpbkf`) a démarré dans la fenêtre ; (2) **Wall compte l'élapsed** (attente + démarrage), pas
les cycles brûlés. Idem pour `future.get(10s)` du publisher : l'attente du round-trip Pub/Sub est
**invisible en CPU, visible en Wall**.

### La vraie leçon

Même sous 2000 réservations, l'app brûle **~1,5 % de CPU**. `kube-train-api` est **I/O-bound**
(Postgres, Pub/Sub), pas CPU-bound. **C'est pour ça que le flame graph CPU montre si peu de code
métier** — et savoir lire ça (« ne cherche pas à optimiser le CPU d'un service qui attend »)
est l'apprentissage central du lab.

---

## 10. Coût

| Ressource | Coût |
|---|---|
| **Cloud Profiler** | **Gratuit** — aucune facturation, quel que soit le volume |
| Surcoût CPU de l'agent | ~négligeable (échantillonnage statistique, conçu pour la prod) |
| **Total lab** | **0 €** |

→ **Confirmé après le run** : aucune ligne Profiler sur la facture, l'agent n'a pas bougé les
ressources du pod (`kube-train-deployment` resté `2/2 Running`).

---

## 11. Blocages rencontrés

- **Swagger : mauvais chemin.** `http://<LB>/swagger/index.html` → 404 (catch-all « Route
  inconnue »). springdoc expose par défaut **`/swagger-ui/index.html`** (UI) et **`/v3/api-docs`**
  (JSON) — les deux **répondent 200 sur GKE** (l'UI n'est pas désactivée en profil `gcp`).
- **`curl -s … > /dev/null` masque le code HTTP** → faux doute « les POST ont-ils échoué ? ».
  Toujours ajouter `-w "%{http_code}\n"` pour compter les statuts (`sort | uniq -c` → `2000 201`).
- **curl séquentiel = charge trop faible.** En série, le CPU respire entre chaque requête → le
  chemin réservation ne ressort pas. Il faut **4 boucles parallèles** (`( … ) &` puis `wait`).
- **Fenêtre profiler non resserrée.** Garder « 30 minutes » mélange le cold-start Spring + le
  `<clinit>` protobuf (one-shot) avec le steady-state → le profil semble plus lourd qu'en régime.
- **Deux pods, `kubectl logs` en cible un seul.** « Found 2 pods, using pod/… » → les logs affichés
  ne couvrent qu'un réplica ; le profil console, lui, agrège les deux.
- **(Windows only) Git Bash convertit les chemins.** Un `-w "/swagger-ui…"` commençant par `/` est
  réécrit en chemin Windows → `export MSYS_NO_PATHCONV=1`. Sans objet depuis WSL.

---

## 12. Cleanup — retour à la baseline

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

## 13. À retenir (matière à QCM)

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
- **CPU vs Wall** : CPU = cycles *brûlés* (Jackson, Hibernate) ; Wall = temps *écoulé*, attente et
  démarrage inclus (round-trip Pub/Sub, cold-start Spring). Une même méthode a deux poids
  radicalement différents selon la métrique. Choisir la métrique **avant** de conclure.
- **Le pattern Outbox déplace le coût hors du chemin requête** : `POST /reservations` reste pauvre
  en CPU (écrit une ligne), le poller de fond porte la sérialisation + la publication. Un profil
  CPU le rend visible d'un coup d'œil.
- **Piège `<clinit>`** : les initialisations de classe (descriptors protobuf, bootstrap Spring) sont
  **one-shot** mais gonflent l'agrégat tant que la fenêtre englobe le démarrage. Resserrer la
  Période pour lire le steady-state.
- **CPU idle ≠ service lent** : `kube-train-api` brûle ~1,5 % CPU même sous charge → il est
  **I/O-bound**. Profiler CPU montre alors surtout la tuyauterie : le vrai levier est côté I/O.
