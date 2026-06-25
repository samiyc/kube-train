# QCM J4 — Istio Service Mesh & Progressive Delivery

**8 questions — Durée estimée : 10-15 min**

---

## Question 1 — [Architecture Istio]
Quelle affirmation décrit correctement l'architecture d'Istio ?

A) Le data plane est porté par `Istiod`, et le control plane par les sidecars Envoy.
B) Le data plane est constitué des proxies Envoy injectés dans les pods ; le control plane est géré par `Istiod`, qui pousse la configuration et les certificats.
C) `VirtualService` remplace le proxy Envoy, qui devient facultatif.
D) Istio n'a pas de control plane : chaque pod calcule sa configuration de routage localement.

---

## Question 2 — [VirtualService]
Dans kube-train, quelle ressource permet de faire un split 90/10 entre `kube-train-api` v1 et v2 ?

A) `VirtualService`, en déclarant deux destinations avec des `weight` différents.
B) `PeerAuthentication`, en définissant `mode: PERMISSIVE`.
C) `ServiceEntry`, en ajoutant deux endpoints internes.
D) `Gateway`, en exposant deux ports NodePort.

---

## Question 3 — [DestinationRule]
À quoi sert principalement une `DestinationRule` dans le scénario canary kube-train ?

A) À injecter automatiquement le sidecar Envoy dans le namespace.
B) À définir les subsets `v1` / `v2` (via labels) et, si besoin, la politique de load balancing appliquée à la destination.
C) À créer le certificat mTLS du service.
D) À limiter l'accès réseau par principal SPIFFE.

---

## Question 4 — [PeerAuthentication]
Quel effet produit une `PeerAuthentication` avec `mtls.mode: STRICT` dans le namespace `kube-train` ?

A) Les workloads mesh doivent parler en mTLS ; un client hors mesh qui tente un appel HTTP direct vers un service meshé sera rejeté.
B) Istio désactive tout chiffrement pour réduire la latence.
C) Les sidecars sont supprimés puis recréés avec un certificat manuel.
D) Le trafic entrant depuis l'Ingress est automatiquement converti en HTTPS applicatif sur le port 443 du conteneur.

---

## Question 5 — [AuthorizationPolicy]
Quelle règle Istio permet de n'autoriser que l'API à appeler `notification-service` ?

A) Une `AuthorizationPolicy` qui filtre `source.principals` sur l'identité SPIFFE/ServiceAccount de `kube-train-api-sa`.
B) Une `DestinationRule` avec `subsets` limités à `v1`.
C) Une `PeerAuthentication` avec `STRICT` sur le namespace.
D) Un `VirtualService` avec `timeout: 1s`.

---

## Question 6 — [Sidecar injection]
Que se passe-t-il quand on labelle le namespace avec `istio-injection=enabled` ?

A) Les pods existants reçoivent immédiatement un conteneur `istio-proxy` sans redémarrage.
B) Le scheduler Kubernetes bascule tous les pods sur des nodes Istio dédiés.
C) Lors de l'admission des nouveaux pods (ou après redémarrage des workloads), un webhook mutating ajoute automatiquement le conteneur Envoy et les volumes/init containers nécessaires.
D) Istio crée automatiquement un `VirtualService` et une `DestinationRule` pour chaque Service.

---

## Question 7 — [Canary deployment]
Quel pattern correspond à un canary progressif propre avec Istio ?

A) Remplacer directement v1 par v2 avec `kubectl replace --force`, puis observer après coup.
B) Déployer v2, router 10% du trafic via `VirtualService`, observer erreurs/latence, puis augmenter progressivement à 25%, 50%, 100% si les indicateurs restent bons.
C) Monter `replicas: 2` sur v2 et laisser Kubernetes répartir le trafic sans labels de version.
D) Désactiver les probes pour éviter que les redémarrages perturbent les statistiques du canary.

---

## Question 8 — [Fault injection]
À quoi sert une faute injectée de type `delay` ou `abort` dans Istio ?

A) À accélérer automatiquement les réponses lentes en contournant le service.
B) À remplacer les tests de charge par des règles de pare-feu.
C) À simuler des incidents réseau/applicatifs (latence, erreurs 5xx) afin de tester la résilience, les timeouts, les retries et l'observabilité.
D) À activer le mode debug permanent d'Envoy sur tous les pods.

---

---

# Correction QCM J4

> Remplir le QCM AVANT de lire cette section.

---

## Q1 — **B** ✅
> Le data plane = proxies Envoy (dans les pods). Le control plane = Istiod (ou Traffic Director en Cloud Service Mesh) qui pousse la configuration via xDS et émet les certificats mTLS.

**Pièges** :
- A : inversé (Istiod = control plane, Envoy = data plane)
- C : VirtualService est une config, pas un proxy. Envoy reste obligatoire.
- D : Sans control plane, les proxies ne sauraient pas où router — il faudrait tout configurer manuellement.

**Point Cloud Service Mesh** : Sur kube-train, le control plane est **Traffic Director** (Google Cloud, pas dans le cluster). Envoy se connecte à `meshconfig.googleapis.com:443` au lieu d'un Istiod local.

---

## Q2 — **A** ✅
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

## Q3 — **B** ✅
> `DestinationRule` définit les **subsets** (groupes de pods filtrés par labels) et les politiques de connexion (mTLS mode, load balancing algorithm, circuit breaker, connection pool).

La `DestinationRule` ne route PAS — elle définit les groupes que le `VirtualService` peut cibler.

**Pièges** :
- A : L'injection du sidecar est gérée par le webhook, pas par DR
- C : Les certificats mTLS sont gérés par `meshca.googleapis.com` (autorité de certification Istio)
- D : C'est le rôle de `AuthorizationPolicy`

**Résultat concret sur kube-train** : Traffic Director a créé 3 clusters Envoy pour `kube-train-service` : `|v1|`, `|v2|`, et `||` (sans subset, fallback). Les stats du cluster prouvent que le routage par subset fonctionne.

---

## Q4 — **A** ✅
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

## Q5 — **A** ✅
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

## Q6 — **C** ✅
> Le webhook **mutating admission** intercepte la création de chaque pod, consulte la configuration d'injection, et ajoute automatiquement le conteneur `istio-proxy`, l'init container `istio-init`, et les volumes nécessaires.

**L'injection a lieu à l'admission du pod** — un pod déjà en cours d'exécution ne reçoit pas le sidecar sans redémarrage.

**Sur kube-train (Cloud Service Mesh)** : le namespace `default` est labelé avec `meshconfig.io/proxy-version=asm-managed-rapid`. Le webhook ASM (pas Istiod) gère l'injection.

**Pièges** :
- A : Les pods EXISTANTS ne reçoivent pas le sidecar → `kubectl rollout restart` obligatoire
- B : Il n'y a pas de nodes Istio dédiés
- D : Istio n'auto-génère PAS de VirtualService/DestinationRule — c'est au développeur de les créer

---

## Q7 — **B** ✅
> Le canary progressif propre avec Istio : déployer v2 → router un faible % → observer les indicateurs (taux d'erreur, latence) → augmenter progressivement.

**L'avantage d'Istio vs K8s natif** : avec Istio, le split est contrôlé indépendamment du nombre de replicas. 1 pod v2 sur 10 replicas totaux ne donne pas forcément 10% — avec Istio, c'est exactement le `weight` configuré.

**Pièges** :
- A : `kubectl replace --force` = déploiement brutal, pas de canary contrôlé
- C : Sans VirtualService ni DestinationRule, K8s répartit équitablement entre TOUS les pods — si v1 a 9 replicas et v2 en a 1, on obtient 90/10, mais c'est fragile et couplé au scaling
- D : Désactiver les probes = risquer de router du trafic vers des pods défaillants

---

## Q8 — **C** ✅
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

**⚠️ Toujours désactiver** après les tests : `kubectl apply -f k8s/istio-canary.yaml` (remet le VirtualService sans fault).

**Pièges** :
- A : Istio ne "contourne" pas les services lents — il simule la lenteur pour tester le comportement client
- B : La fault injection est l'opposé d'un pare-feu — elle laisse passer mais dégrade
- D : Envoy n'a pas de "mode debug permanent" via fault injection

