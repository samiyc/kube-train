# Correction Examen Ouvert J1 — Sécurité Kubernetes & RBAC
> Date : 09/06/2026 | Score : **6.5 / 10**

---

## Question 1 — PSS : niveaux et modes ⭐ → **0.75 / 1**

### a) Les trois paires mode/niveau ✅ Correct (avec un piège de vocabulaire)

Les modes sont bien décrits dans le fond. Deux points de précision :

**Typo "warm" → "warn"** — erreur de frappe récurrente dans tes réponses. En entretien oral ce n'est pas grave, mais à l'écrit c'est le terme exact `warn`.

**Distinction audit vs warn** — ce ne sont PAS le même mécanisme "plus ou moins approfondi" :

| Mode | Où l'information apparaît | Qui la voit |
|---|---|---|
| `warn` | Dans la réponse de l'API (kubectl affiche un warning côté client) | Le développeur qui fait `kubectl apply` |
| `audit` | Dans le journal d'audit K8s (`/var/log/kubernetes/audit.log`) | L'équipe sécurité / SRE en post-mortem |

Un pod qui viole PSS restricted mais respecte PSS baseline :
- Reçoit un ⚠️ `Warning` visible dans le terminal (`warn`)
- Génère une entrée dans l'audit log avec `policy.kubernetes.io/audit-violations` (`audit`)
- **N'est pas bloqué** (enforce=baseline ne couvre pas cette violation)

### b) Pourquoi enforce=baseline et non restricted ✅ Partiel

La réponse est correcte dans l'esprit. La raison concrète à citer en entretien :

> "Plusieurs pods dans le namespace `default` ne sont pas compatibles PSS restricted (postgres n'a pas de `seccompProfile`, le node-exporter du DaemonSet tourne en `hostPID`). Enforcer `restricted` bloquerait ces pods aujourd'hui. La stratégie est : enforcer `baseline` maintenant (bloque le pire), et utiliser `warn/audit=restricted` pour identifier ce qu'il reste à corriger avant de monter enforce à restricted."

C'est une migration progressive contrainte par l'existant, pas un simple "test en dev".

### c) Effet concret sans enforce=restricted ✅ Correct dans l'esprit

Compléter avec les observables concrets : le pod qui viole restricted **est créé et démarre** (enforce=baseline ne le bloque pas), mais `kubectl apply` affiche un warning dans le terminal, et une annotation est ajoutée dans l'audit log. En prod, cela permet de tracer les violations sans interrompre le service.

---

## Question 2 — ServiceAccounts : stratégie de découpage ⭐ → **0.5 / 1**

### a) Les deux SA dédiés ⚠️ Incomplet

Le principe est correct mais la question demandait de **nommer** les SA :

- `kube-train-api-sa` → pour l'API Spring Boot (défini dans `k8s/security/rbac.yaml`)
- `notification-sa` → pour le service notification (défini dans `k8s/security/rbac-gke.yaml`)

Savoir nommer les objets exacts est attendu en entretien CKAD.

### b) Risque du SA default ✅ Correct

Le scénario est bon. Pour l'affiner en entretien : préciser le **vecteur** d'exploitation.

Un attaquant qui compromet le pod kube-train-api (RCE, SSRF…) peut utiliser le token SA monté dans `/var/run/secrets/kubernetes.io/serviceaccount/token` pour appeler l'API K8s avec les droits du SA. Si c'est `default` partagé avec notification-service (qui a besoin d'accès Pub/Sub via Workload Identity), l'attaquant hérite de ces droits GCP également.

### c) Rôle de automountServiceAccountToken: false ❌ Hors sujet

La réponse parle du SA `default` en général — ce n'est pas la question.

**Ce que fait `automountServiceAccountToken: false`** : par défaut, K8s monte automatiquement un JWT token dans chaque pod (dans `/var/run/secrets/kubernetes.io/serviceaccount/`). Ce token permet au processus dans le pod de s'authentifier auprès de l'API K8s.

**Pourquoi le désactiver ici** : kube-train-api n'a pas besoin d'appeler l'API K8s directement. Sur GKE, l'authentification aux APIs GCP passe par le metadata server (Workload Identity), pas par ce token. En le désactivant, si un attaquant compromet le container, il ne peut pas utiliser ce token pour interagir avec l'API K8s (lister les pods, lire d'autres secrets, etc.).

**Règle** : désactiver par défaut, réactiver uniquement si le pod a besoin d'appeler l'API K8s (operators, controllers, etc.).

---

## Question 3 — LimitRange vs ResourceQuota ⭐ → **0.75 / 1**

### a) Kind, niveau, rôle ✅ Correct (forme à améliorer)

Correct dans le fond. Format attendu pour un entretien oral :

| Kind | Niveau d'application | Rôle |
|---|---|---|
| `LimitRange` | Container (individuel) | Injecte des defaults CPU/RAM si absent, pose min/max par container |
| `ResourceQuota` | Namespace (agrégé) | Plafonne la consommation totale du namespace (pods, CPU, RAM) |

### b) defaultRequest vs default ⚠️ Incomplet

L'incertitude dans la réponse ("defaultRequest utilisé par k8s ?") montre que le mécanisme n'est pas totalement clair. Voici la distinction précise :

Dans `quota.yaml` :
```yaml
defaultRequest:
  cpu: 100m     # valeur injectée dans resources.requests si absente du manifest
  memory: 256Mi
default:
  cpu: 500m     # valeur injectée dans resources.limits si absente du manifest
  memory: 512Mi
```

Un container sans `resources:` se voit injecter **les deux** : requests=100m/256Mi ET limits=500m/512Mi. K8s ne démarre pas un container sans limits si une ResourceQuota est active — le LimitRange est le filet de sécurité qui empêche le rejet.

### c) Complémentarité ✅ Correct

Bonne réponse. Pour compléter : sans ResourceQuota, même avec un LimitRange parfait, un développeur peut créer 500 pods dans le namespace — chacun dans les bornes du LimitRange, mais la somme peut épuiser le cluster. Les deux objets sont nécessaires.

---

## Question 4 — securityContext : classification complète ⭐⭐ → **0.5 / 1**

### a) Liste complète des champs ✅ Correct

Liste exhaustive et correctement classifiée — c'est la bonne réponse.

### b) Pourquoi fsGroup ne peut pas être container-level ❌ Trop court

"fsGroup est lié au File Système" — c'est vrai mais n'explique pas le **pourquoi** technique.

**Explication complète** :

`fsGroup` configure le GID (group ID) appliqué aux volumes montés dans le pod. Un volume (`emptyDir`, PVC…) est déclaré dans `spec.volumes[]` — c'est un objet **pod-level**, pas container-level. Plusieurs containers du même pod peuvent monter ce volume. Si chaque container pouvait définir son propre `fsGroup`, K8s ne saurait pas quel GID appliquer au volume qui est partagé entre eux.

C'est pourquoi `fsGroup` est intrinsèquement pod-level : il s'applique à la couche de stockage du pod, pas à un processus isolé.

**Mémo** : tout ce qui concerne les volumes → pod-level. Tout ce qui concerne le processus du container → container-level.

### c) Niveau de seccompProfile et cas de surcharge ⚠️ Incomplet

Tu identifies la valeur (`RuntimeDefault`) mais pas le niveau ni le cas de surcharge.

- **Niveau** : `seccompProfile` est ici au niveau **pod** (`spec.securityContext.seccompProfile`)
- **Quand surcharger au niveau container** : si un container du pod nécessite un profil plus strict (profil `Localhost` avec une liste de syscalls réduite pour un container applicatif critique), ou si un sidecar doit temporairement utiliser `Unconfined` pour des raisons de compatibilité — on override pour ce seul container sans changer le profil du pod entier.

---

## Question 5 — RBAC : verbes et resourceNames ⭐⭐ → **1 / 1**

### a) get vs list ✅ Correct

Réponse correcte. Nuance à ajouter pour l'entretien : `list` sur les secrets retourne **les valeurs** de tous les secrets du namespace, pas seulement leurs noms. C'est une exfiltration de données complète en une seule requête — d'où le danger.

### b) Sans resourceNames ✅ Correct

### c) Les trois commandes ✅ Toutes correctes

Les trois réponses (yes / no / no) sont exactes et les justifications sont précises.

---

## Question 6 — Init container : états du pod ⭐⭐ → **0.5 / 1**

### a) STATUS quand postgres est down ❌ Incorrect

**Réponse fournie** : `Pending`  
**Réponse correcte** : `Init:0/1`

`Pending` = le pod n'a pas encore été schedulé sur un node (en attente du scheduler). Une fois schedulé et l'init container en cours d'exécution (même en boucle), le STATUS passe à `Init:0/1` (init container 0 réussi sur 1 attendu).

```
STATUS   : Init:0/1
READY    : 0/1
RESTARTS : 0
```

C'est une erreur fréquente en examen CKAD — `Pending` a une signification précise.

### b) Après succès init container, Spring Boot démarre ⚠️ Partiel

**Réponse correcte complète** :

```
Init container OK → STATUS: PodInitializing (quelques secondes)
Spring Boot démarre  → STATUS: Running (READY: 0/1 tant que readinessProbe échoue)
Readiness OK         → STATUS: Running (READY: 1/1)
```

`Running` est correct pour l'état final, mais l'état intermédiaire `PodInitializing` et la distinction `READY: 0/1` → `READY: 1/1` sont importants en debugging.

### c) Image busybox:1.36 introuvable ✅ Correct

`ErrImagePull` au premier essai, puis `ImagePullBackOff` (backoff exponentiel). Les deux sont acceptables.

### d) Init container OK mais Spring Boot crashe ❌ Incorrect

**Réponse fournie** : `Failed`  
**Réponse correcte** : `CrashLoopBackOff`

`Failed` = tous les containers du pod se sont terminés, au moins un avec un exit code non-zero, et la restartPolicy est `Never` ou `OnFailure` avec le maximum de retries atteint.

Pour un Deployment (restartPolicy=Always implicite), K8s redémarre le container en boucle avec un backoff exponentiel → `CrashLoopBackOff`.

```
Séquence : Running (Error) → Running (Error) → Running (CrashLoopBackOff)
STATUS kubectl get pods : CrashLoopBackOff
```

**Mémo** :
- `Failed` → pod terminé définitivement (Job avec restartPolicy:Never)
- `CrashLoopBackOff` → container qui crashe et redémarre en boucle (Deployment)

---

## Question 7 — readOnlyRootFilesystem et volumes ⭐⭐ → **0.75 / 1**

### a) Ce qu'un processus ne peut plus faire ✅ Correct (1 exemple au lieu de 2)

La réponse couvre l'essentiel. Second exemple attendu : un attaquant qui compromet le container ne peut pas modifier les binaires (`/bin/sh`, `/usr/bin/curl`…) pour persister — le filesystem est en lecture seule. `readOnlyRootFilesystem` est donc aussi une defense-in-depth contre les techniques de persistence post-exploitation.

### b) Chaîne causale Spring Boot → /tmp → emptyDir ✅ Correct dans l'esprit

La réponse capture l'essentiel. Pour être précis :

1. `readOnlyRootFilesystem: true` rend le filesystem du container (toutes les couches de l'image Docker) en lecture seule — y compris `/tmp` qui fait partie du layer de l'image
2. La JVM (Spring Boot) a besoin d'écrire dans `/tmp` pour : les classes extracted du fat JAR (class loader), les fichiers temporaires Tomcat, les sockets Unix OTel
3. `JAVA_TOOL_OPTIONS: -Djava.io.tmpdir=/tmp` redirige le tmpdir JVM vers `/tmp`
4. Le volume `emptyDir` monté sur `/tmp` **remplace** le `/tmp` en lecture seule par un répertoire vide et writable

### c) emptyDir vs hostPath vs PVC ⚠️ Partiel

L'aspect éphémère est correct. Le point manquant :

**hostPath** : monterait un répertoire du node hôte dans le container. Problèmes : le container peut lire/écrire des fichiers du host (élévation de privilège), le contenu persiste entre les pods (fuite de données entre workloads), et PSS baseline/restricted interdit `hostPath`. C'est précisément le type de volume que PSS vise à éliminer.

**PVC** : persistant, coûteux sur GKE (SSD facturation), nécessite un PV provisionné. Sur-dimensionné pour des fichiers temporaires JVM qui doivent vivre le temps du pod.

**emptyDir** : créé vide au démarrage du pod, détruit à sa suppression, totalement isolé, gratuit. Parfait pour les fichiers temporaires d'un processus.

---

## Question 8 — Calcul ResourceQuota et rolling update ⭐⭐⭐ → **0.5 / 1**

### a) Calcul maxSurge=1 / maxUnavailable=1 ⚠️ Résultat correct, raisonnement erroné

**Résultat** : 6 pods → ✅ correct.  
**Raisonnement** : ❌ `maxUnavailable` a été compté comme un pod supplémentaire — c'est faux.

`maxUnavailable` = nombre de pods qui peuvent être **indisponibles** simultanément (en cours de suppression ou non-ready). Il ne crée pas de pod — il permet de **supprimer** un ancien pod. `maxSurge` = nombre de pods **supplémentaires** au-delà de replicas.

**Calcul correct** :

```
Pods cible : replicas = 3
Max pods API pendant rolling : replicas + maxSurge = 3 + 1 = 4
Autres pods : node-exporter (1) + postgres (1) = 2
Peak total : 4 + 2 = 6
Quota 6 : 6 = 6 → ✅ suffisant, mais sans marge
```

**Analyse de la prochaine mise à jour** (également incorrecte dans ta réponse) :

Après migration vers replicas=3, état stable = 5 pods (3+1+1). Lors du prochain rolling update :
`3 + 1(maxSurge) + 2(autres) = 6` → toujours dans la quota. Pas de problème.

### b) maxSurge=1 / maxUnavailable=0 ❌ Conclusion erronée

**Réponse fournie** : "le déploiement ne pourra pas se terminer correctement → CrashLoopBackOff"  
**Réponse correcte** : le rolling update **se déroule correctement**, juste plus lentement.

> **Question complémentaire — quel STATUS quand la quota est dépassée ?**
>
> Contrairement à ce qu'on pourrait penser, **aucun nouveau pod n'apparaît** dans `kubectl get pods`.
> La ResourceQuota est appliquée par l'**admission controller** avant même que le pod soit persisté en etcd.
> La création est rejetée avec un `403 Forbidden` — l'objet pod n'existe pas.
>
> Ce qu'on observe :
> ```bash
> kubectl get pods
> # → seuls les pods existants sont listés (aucun nouveau)
>
> kubectl get events --sort-by=.lastTimestamp | tail -5
> # → Warning  FailedCreate  replicaset-controller
> #   Error creating: pods "..." is forbidden: exceeded quota: kube-train-quota,
> #   requested: pods=1, used: pods=6, limited: pods=6
>
> kubectl describe replicaset <rs-name>
> # → Events: FailedCreate (répété toutes ~10s par le controller)
>
> kubectl rollout status deployment/kube-train-deployment
> # → bloqué indéfiniment → finira en ProgressDeadlineExceeded (~10 min)
> ```
>
> **Résumé** : quota insuffisante → pas de pod visible → erreur dans les événements du ReplicaSet.

Avec `maxUnavailable=0` : aucun ancien pod n'est tué avant qu'un nouveau soit Ready.  
Avec `maxSurge=1` : au plus 1 pod supplémentaire au-dessus de replicas.

**Séquence** (replicas 2 → 3) :
```
État initial : 2 old + 2 autres = 4 pods
Étape 1 : créer 1 new (surge) → 2 old + 1 new + 2 autres = 5 pods
           attendre new Ready → tuer 1 old → 1 old + 1 new + 2 autres = 4 pods
Étape 2 : créer 1 new → 1 old + 2 new + 2 autres = 5 pods
           attendre Ready → tuer 1 old → 0 old + 2 new + 2 autres = 4 pods
Étape 3 : créer 1 new → 0 old + 3 new + 2 autres = 5 pods
           (pas d'ancien à tuer) → terminé ✅
```

Maximum simultané = 5 pods < quota 6 → rolling update possible.  
`CrashLoopBackOff` est un état de pod (container qui crashe), pas une conséquence de quota insuffisante.

### c) Vérification requests.cpu ✅ Correct

`3 × 200m + 100m + 100m = 800m < 2000m` → quota respectée.

> **Question complémentaire — faut-il prévoir de la marge sur requests.cpu ?**
>
> 800m sur une quota de 2000m = **40% d'utilisation en régime stable** → très confortable.
> Pendant le rolling update (pic 4 pods API) : `4 × 200m + 200m = 1000m` = 50% → toujours OK.
>
> **En production**, la marge sert à absorber :
> - Le pic du rolling update (`maxSurge`) — calculé ci-dessus
> - Une montée en charge HPA (Horizontal Pod Autoscaler) qui ajoute des replicas
> - Un redémarrage d'urgence (`kubectl rollout restart`) qui crée temporairement des pods supplémentaires
>
> **Règle pratique** : `requests.cpu` quota ≥ `replicas_max × cpu_request × 1.5`  
> Ici : `3 × 200m × 1.5 = 900m` — on a 2000m, très largement suffisant.
>
> **Rappel** : `requests.cpu` est une **réservation de scheduling**, pas un cap d'exécution.
> Une quota trop serrée empêche le scheduling même si les nodes ont de la capacité disponible.
> C'est `limits.cpu` qui briderait l'exécution réelle (throttling).

---

## Question 9 — Deadlock sidecar / init container ⭐⭐⭐ → **0.25 / 1**

### a) Mécanisme précis du deadlock ❌ Manqué

La réponse décrit un problème différent (la base de données inaccessible). Le deadlock réel :

**Règle fondamentale K8s** : tous les `initContainers` doivent se terminer avec succès **avant** que K8s démarre le premier container de `spec.containers[]`.

**Scénario du deadlock** :
```
1. Pod schedulé
2. Init container wait-for-postgres démarre et fait : until nc -z 127.0.0.1 5432
3. 127.0.0.1:5432 = Cloud SQL Proxy (le sidecar dans spec.containers[])
4. Or : Cloud SQL Proxy ne démarre PAS encore — K8s attend que l'init container finisse
5. Init container attend que Cloud SQL Proxy écoute sur :5432
6. Cloud SQL Proxy attend que l'init container finisse

→ DEADLOCK : chacun attend l'autre indéfiniment
→ Pod reste en Init:0/1 pour toujours
```

Ce n'est pas un problème de base de données — c'est un problème d'**ordre de démarrage** entre init containers et containers applicatifs.

### b) Solution dans kube-train ⚠️ Partiel

La réponse décrit le comportement mais pas pourquoi il évite le deadlock.

**Sur Minikube** (`deployment.yaml`) : l'init container attend `postgres-service:5432` (un Service K8s, pas localhost). PostgreSQL tourne dans un pod séparé (pas un sidecar du même pod), donc il n'y a pas de dépendance circulaire.

**Sur GKE** (`deployment-gke.yaml`) : il n'y a **pas** d'init container. Cloud SQL Proxy est un sidecar dans `spec.containers[]`. Spring Boot se connecte à `127.0.0.1:5432` et retry nativement au démarrage. L'init container n'est pas nécessaire car le Proxy démarre en parallèle et Spring Boot gère lui-même l'attente via les retry de HikariCP.

**La règle** : ne jamais faire attendre un init container sur `localhost` si ce service est un sidecar du même pod.

### c) Fonctionnalité K8s 1.28+ ✅ Correct

`restartPolicy: Always` dans un `initContainer` → sidecar natif. Il démarre **avant** les autres init containers classiques et reste actif pendant toute la vie du pod. Cela résout le deadlock car le sidecar natif est déjà en cours d'exécution quand les init containers classiques démarrent.

---

## Question 10 — Audit de sécurité RBAC ⭐⭐⭐ → **0.75 / 1**

### a) Opérations nouvellement autorisées ✅ Correct (précision à ajouter)

Correct. Pour être exhaustif :

La configuration proposée supprime **deux protections** simultanément :
1. **`resourceNames` supprimé** → `kube-train-api-sa` peut `get` **n'importe quel secret** du namespace (pas seulement `kube-train-secrets`)
2. **Verbes ajoutés** → `list` (énumère TOUS les secrets avec leurs valeurs) et `watch` (reçoit toutes les modifications en temps réel)

Nouvelles opérations autorisées :
- `get` sur `db-root-password`, `tls-cert`, `registry-credentials`, tout autre secret du namespace
- `list secrets` → retourne le contenu de tous les secrets en une requête
- `watch secrets` → flux en temps réel de tous les changements de secrets

### b) Scénario d'attaque ✅ Correct

Bon scénario. Pour le rendre plus percutant en entretien, préciser le vecteur technique :

```
1. Attaquant exploite une faille RCE dans l'API (ex: dependency vulnérable)
2. Exec dans le container : kubectl exec -it <pod> -- sh
3. Récupère le token SA monté (même avec automountServiceAccountToken:false,
   si l'attaquant a exec il est déjà dans le process — il peut utiliser curl)
   cat /var/run/secrets/kubernetes.io/serviceaccount/token
4. Appel API K8s depuis le container :
   curl -H "Authorization: Bearer $TOKEN" \
     https://kubernetes.default/api/v1/namespaces/default/secrets
5. Reçoit db-root-password en clair (base64 décodable) → accès DB complet
```

### c) Les deux commandes révélatrices ⚠️ Partiel

Une seule comparaison fournie (get secret/db-root-password). La paire la plus révélatrice aurait été :

```bash
# Commande 1 — la plus dangereuse : list expose TOUS les secrets d'un coup
kubectl auth can-i list secrets \
  --as=system:serviceaccount:default:kube-train-api-sa
# Avant → no | Après → yes

# Commande 2 — accès à un secret arbitraire (sans resourceNames)
kubectl auth can-i get secret/db-root-password \
  --as=system:serviceaccount:default:kube-train-api-sa
# Avant → no | Après → yes
```

Ta commande est valide mais `list secrets` est plus révélatrice car elle illustre l'exfiltration de masse.

### d) Cas légitime pour list ❌ Manqué

La réponse ("si le resourceNames est renseigné ça limite la casse") répond à "comment mitiger list" et non à "quand list est légitime".

**Cas légitimes** où `list` sur les secrets est réellement nécessaire :
- **Operators / Controllers** : un Secret Rotation Controller qui tourne en boucle pour détecter les secrets expirés doit lister tous les secrets pour trouver ceux à renouveler
- **Cert-manager** : gère les certificats TLS sous forme de secrets — a besoin de lister/watch les secrets pour détecter les expirations
- **Vault Agent Injector** : doit surveiller les changements de secrets pour les propager aux pods
- **Backup operator** : doit lister tous les secrets pour les inclure dans une sauvegarde chiffrée du namespace

Dans ces cas, `list` est justifié et `resourceNames` ne peut pas être utilisé (par définition, ces opérateurs ne connaissent pas les noms à l'avance).

---

## Score final

| Question | Sujet | Niveau | Score | Note |
|---|---|---|---|---|
| Q1 | PSS modes et niveaux | ⭐ | **0.75** | Modes corrects, profondeur insuffisante sur b/c |
| Q2 | ServiceAccount découpage | ⭐ | **0.5** | Noms SA manquants, automountToken completement raté |
| Q3 | LimitRange vs ResourceQuota | ⭐ | **0.75** | defaultRequest flou, reste correct |
| Q4 | securityContext classification | ⭐⭐ | **0.5** | Liste parfaite, fsGroup et seccompProfile trop courts |
| Q5 | RBAC verbes + resourceNames | ⭐⭐ | **1.0** | Toutes les réponses correctes |
| Q6 | Init container états du pod | ⭐⭐ | **0.5** | Init:0/1 ≠ Pending, CrashLoopBackOff ≠ Failed |
| Q7 | readOnlyRootFilesystem | ⭐⭐ | **0.75** | Bien, hostPath security manquant |
| Q8 | Calcul quota rolling update | ⭐⭐⭐ | **0.5** | Résultat OK, raisonnement faux (maxUnavailable) |
| Q9 | Deadlock sidecar/init | ⭐⭐⭐ | **0.25** | Mécanisme manqué, restartPolicy correct |
| Q10 | Audit RBAC | ⭐⭐⭐ | **0.75** | Scénario bon, list légitime manqué |
| **TOTAL** | | | **6.5 / 10** | |

---

## Points forts

- **Q5 parfaite** : maîtrise complète de RBAC verbes + resourceNames + kubectl auth can-i
- **Q7 solide** : readOnlyRootFilesystem bien compris dans la pratique
- **Q9c** : restartPolicy:Always trouvé (avec source K8s blog — bonne démarche)
- **Q8c** : calcul CPU rigoureux et correct
- **Q10b** : scénario d'attaque bien construit

## Points à retenir avant entretien

### 1. Les STATUS kubectl get pods — à mémoriser

```
Pending        → pas encore schedulé sur un node
Init:0/1       → init container en cours (pas réussi)
PodInitializing → init OK, containers principaux démarrent
Running        → container principal actif
CrashLoopBackOff → container qui crashe et redémarre
ImagePullBackOff → image introuvable ou accès refusé
Failed         → pod terminé définitivement (Job uniquement)
```

### 2. maxSurge vs maxUnavailable — la règle

```
Peak pods = replicas_cible + maxSurge   (maxUnavailable ne CRÉE pas de pods)
```

### 3. Deadlock sidecar/init — la règle

> "Un init container qui attend localhost:<port> alors que ce service est un sidecar du même pod → deadlock. Le sidecar ne démarre jamais tant que l'init container n'a pas fini."

### 4. automountServiceAccountToken: false

> "Désactiver le montage du token SA quand le pod n'appelle pas l'API K8s directement. Le token est le pass d'accès au cluster — l'exposer sans raison agrandit la surface d'attaque."

### 5. fsGroup — la règle

> "fsGroup s'applique aux volumes, les volumes sont pod-level → fsGroup est pod-level. Container-level n'a pas de sens car plusieurs containers peuvent monter le même volume."
