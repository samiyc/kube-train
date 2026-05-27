# QCM J4 — Sécurité applicative (OAuth2, NetworkPolicies, Trivy)

**8 questions — Durée estimée : 10-15 min**

---

## Question 1 — Le triangle OAuth2

Dans l'architecture OAuth2, quel rôle joue notre `kube-train-api` ?

A) Authorization Server — elle émet les tokens JWT  
B) Resource Server — elle valide les tokens JWT et protège les ressources  
C) Client — elle obtient les tokens auprès de Keycloak  
D) Identity Provider — elle gère les identités des utilisateurs  

---

## Question 2 — Spring Security : Resource Server

Quelle annotation/configuration active le mode "Resource Server" dans Spring Boot 4 ?

A) `@EnableOAuth2Sso`  
B) `.oauth2Login(Customizer.withDefaults())`  
C) `.oauth2ResourceServer(oauth2 -> oauth2.jwt(...))`  
D) `@EnableAuthorizationServer`  

---

## Question 3 — Profile "secured" : stratégie

Pourquoi utilise-t-on `@Profile("secured")` sur `SecurityConfig` et `@Profile("!secured")` sur `PermissiveSecurityConfig` ?

A) Pour activer OAuth2 uniquement en production GKE  
B) Pour avoir deux configurations mutuellement exclusives : tests/dev sans auth, prod avec JWT  
C) Parce que Spring Boot 4 interdit d'avoir deux `SecurityFilterChain` dans le même contexte  
D) Pour désactiver Swagger en production  

---

## Question 4 — JWT : structure

Un JWT est composé de 3 parties séparées par des `.`. Quelle partie contient les claims (`sub`, `iss`, `exp`, `preferred_username`) ?

A) Header (1ère partie)  
B) Payload (2ème partie)  
C) Signature (3ème partie)  
D) Tout le token est chiffré, il n'y a pas de parties lisibles  

---

## Question 5 — NetworkPolicy : comportement par défaut

Que se passe-t-il si on applique `default-deny-ingress` (qui bloque tout le trafic entrant) SANS ajouter de NetworkPolicy "allow" pour l'API ?

A) Les pods redémarrent car les liveness probes échouent  
B) Le Service LoadBalancer redirige le trafic vers un autre pod automatiquement  
C) Rien, les NetworkPolicies sont ignorées si elles sont trop restrictives  
D) Le pod reste en Running mais est inaccessible — timeout côté client  

---

## Question 6 — NetworkPolicy : trafic kubelet

Sur GKE Autopilot, les health probes (startupProbe, livenessProbe, readinessProbe) viennent de quelle source ?

A) D'un pod de monitoring dans le namespace `kube-system`  
B) Des IPs des nodes (hors cluster) → nécessite `ipBlock: cidr: 0.0.0.0/0`  
C) Du Service LoadBalancer (`34.38.x.x`)  
D) Du container lui-même (loopback `127.0.0.1`)  

---

## Question 7 — Trivy : CVE et CI/CD

Dans notre pipeline, Trivy scanne les images Docker et produit un rapport JSON. Quelle est la stratégie adoptée pour ne pas bloquer les déploiements tout en restant visible ?

A) Les CVE CRITICAL font échouer le build (`exit code 1`)  
B) `continue-on-error: true` + parsing JSON avec `jq` → annotations `::warning::` + résumé markdown dans `$GITHUB_STEP_SUMMARY`  
C) Les CVE sont ignorées complètement avec `.trivyignore`  
D) Trivy est lancé uniquement en mode "audit" qui ne renvoie jamais d'erreur  

---

## Question 8 — Headers OWASP

Parmi ces headers de sécurité ajoutés par `PermissiveSecurityConfig` et `SecurityConfig`, lequel protège contre le **clickjacking** (embedding de ta page dans une iframe malveillante) ?

A) `X-Content-Type-Options: nosniff`  
B) `Strict-Transport-Security`  
C) `X-Frame-Options: DENY`  
D) `X-XSS-Protection: 0`  
