# Correction QCM J4 — Istio Service Mesh & Progressive Delivery
> Date : 25/06/2026 | Score : **8 / 8** (100 %)

---

## Q1 — Architecture Istio

**Ta réponse : B ✅ CORRECT**

> Le data plane = proxies Envoy (dans les pods). Le control plane = Istiod (ou Traffic Director en Cloud Service Mesh) qui pousse la configuration via xDS et émet les certificats mTLS.

**Pièges** :
- A : inversé (Istiod = control plane, Envoy = data plane)
- C : VirtualService est une config, pas un proxy. Envoy reste obligatoire.
- D : Sans control plane, les proxies ne sauraient pas où router — il faudrait tout configurer manuellement.

**Point Cloud Service Mesh** : Sur kube-train, le control plane est **Traffic Director** (Google Cloud, pas dans le cluster). Envoy se connecte à `meshconfig.googleapis.com:443` au lieu d'un Istiod local.

---

## Q2 — VirtualService

**Ta réponse : A ✅ CORRECT**

> `VirtualService` avec deux destinations et des `weight` distincts. C'est la ressource de routage du trafic dans Istio.

```yaml
http:
  - route:
      - destination: { host: kube-train-service, subset: v1 }
        weight: 90
      - destination: { host: kube-train-service, subset: v2 }
        weight: 10
```

**Pièges** :
- B : `PeerAuthentication` gère le mTLS, pas le routage
- C : `ServiceEntry` ajoute des services **externes** au mesh (ex : API tierce)
- D : `Gateway` expose le trafic externe entrant — pas le routage interne

**Stats réelles observées** : `rq_total::141` (v1) + `rq_total::9` (v2) = 6% pour v2 sur 150 requêtes (attendu 10% — variance statistique normale sur petit échantillon).

---

## Q3 — DestinationRule

**Ta réponse : B ✅ CORRECT**

> `DestinationRule` définit les **subsets** (groupes de pods filtrés par labels) et les politiques de connexion (mTLS mode, load balancing algorithm, circuit breaker, connection pool).

La `DestinationRule` ne route PAS — elle définit les groupes que le `VirtualService` peut cibler.

**Pièges** :
- A : L'injection du sidecar est gérée par le webhook, pas par DR
- C : Les certificats mTLS sont gérés par `meshca.googleapis.com` (autorité de certification Istio)
- D : C'est le rôle de `AuthorizationPolicy`

**Résultat concret sur kube-train** : Traffic Director a créé 3 clusters Envoy pour `kube-train-service` : `|v1|`, `|v2|`, et `||` (sans subset, fallback). Les stats du cluster prouvent que le routage par subset fonctionne.

---

## Q4 — PeerAuthentication

**Ta réponse : A ✅ CORRECT**

> Mode STRICT = tout le trafic **entrant** sur un workload meshé doit utiliser mTLS. Un pod sans sidecar (pas de certificat SPIFFE) ne peut pas se connecter — la connexion est réinitialisée au niveau TCP.

**Test réel** :
- `plain-client` (1/1, pas de sidecar) → `Connection reset by peer` ✅
- `mesh-client` (2/2, sidecar + certificat) → HTTP 200 ✅

**Pièges** :
- B : STRICT augmente la sécurité, pas la latence (Envoy chiffre sans coût notable sur les CPUs modernes)
- C : Les sidecars ne sont pas recréés — la politique change la configuration Envoy existante
- D : STRICT concerne le trafic inter-pods (east-west), pas l'Ingress (north-south)

**Point Cloud Service Mesh** : La propagation du mode STRICT via Traffic Director prend **30 à 90 secondes**. Un test immédiat après `kubectl apply` donnera un faux positif (le mode n'est pas encore actif).

---

## Q5 — AuthorizationPolicy

**Ta réponse : A ✅ CORRECT**

> `AuthorizationPolicy` avec `source.principals` filtre par **identité SPIFFE** (dérivée du ServiceAccount K8s).

```yaml
rules:
  - from:
      - source:
          principals: ["cluster.local/ns/default/sa/kube-train-api-sa"]
```

**Format du principal** : `cluster.local/ns/<namespace>/sa/<serviceaccount>` — c'est l'URI SPIFFE sans le préfixe `spiffe://`.

**Test réel** :
- `mesh-client` (SA `kube-train-api-sa`) → HTTP 200 ✅
- `other-client` (SA `default`, non autorisé) → `HTTP 403 RBAC: access denied` ✅

**Règle critique** : dès qu'une ALLOW policy existe sur un workload, tout ce qui ne matche pas est implicitement refusé — comportement allowlist.

**Pièges** :
- B : `DestinationRule` avec subsets = routage, pas autorisation
- C : `PeerAuthentication STRICT` vérifie QUE la connexion est mTLS, pas QUI appelle
- D : `VirtualService timeout` = résilience, pas autorisation

---

## Q6 — Sidecar injection

**Ta réponse : C ✅ CORRECT**

> Le webhook **mutating admission** intercepte la création de chaque pod, consulte la configuration d'injection, et ajoute automatiquement le conteneur `istio-proxy`, l'init container `istio-init`, et les volumes nécessaires.

**L'injection a lieu à l'admission du pod** — un pod déjà en cours d'exécution ne reçoit pas le sidecar sans redémarrage.

**Sur kube-train (Cloud Service Mesh)** : le namespace `default` est labelé avec `meshconfig.io/proxy-version=asm-managed-rapid`. Le webhook ASM (pas Istiod) gère l'injection.

**Pièges** :
- A : Les pods EXISTANTS ne reçoivent pas le sidecar → `kubectl rollout restart` obligatoire
- B : Il n'y a pas de nodes Istio dédiés
- D : Istio n'auto-génère PAS de VirtualService/DestinationRule — c'est au développeur de les créer

---

## Q7 — Canary deployment

**Ta réponse : B ✅ CORRECT**

> Le canary progressif propre avec Istio : déployer v2 → router un faible % → observer les indicateurs (taux d'erreur, latence) → augmenter progressivement.

**L'avantage d'Istio vs K8s natif** : avec Istio, le split est contrôlé indépendamment du nombre de replicas. 1 pod v2 sur 10 replicas totaux ne donne pas forcément 10% — avec Istio, c'est exactement le `weight` configuré.

**Pièges** :
- A : `kubectl replace --force` = déploiement brutal, pas de canary contrôlé
- C : Sans VirtualService ni DestinationRule, K8s répartit équitablement entre TOUS les pods — si v1 a 9 replicas et v2 en a 1, on obtient 90/10, mais c'est fragile et couplé au scaling
- D : Désactiver les probes = risquer de router du trafic vers des pods défaillants

---

## Q8 — Fault injection

**Ta réponse : C ✅ CORRECT**

> La fault injection sert à valider la **résilience** de l'application face à des conditions adverses, en les simulant de façon contrôlée et réversible.

**Types de fautes Istio** :
- `delay` (fixedDelay ou exponentialDelay) : simule de la latence réseau
- `abort` (httpStatus) : simule des erreurs HTTP (500, 503)

**Test réel** : 500ms de `fixedDelay` sur `/reservations` → mesure réelle de 980ms (vs ~450ms sans injection).

**Ce qu'on teste avec la fault injection** :
- Les timeouts clients sont-ils correctement configurés ?
- Les retries sont-ils paramétrés (et fonctionnent-ils) ?
- L'observabilité (alertes, dashboards) détecte-t-elle la dégradation ?
- L'application dégrade-t-elle gracieusement (circuit breaker) ?

**⚠️ Toujours désactiver** après les tests : `kubectl apply -f k8s/istio/istio-canary.yaml` (remet le VirtualService sans fault).

**Pièges** :
- A : Istio ne "contourne" pas les services lents — il simule la lenteur pour tester le comportement client
- B : La fault injection est l'opposé d'un pare-feu — elle laisse passer mais dégrade
- D : Envoy n'a pas de "mode debug permanent" via fault injection
