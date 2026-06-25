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
