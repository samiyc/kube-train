# Notes J4 — Istio Service Mesh & Progressive Delivery

> Formation F4 — Platform Engineering  
> Objectif : ajouter un service mesh pour le mTLS automatique, le traffic management et le canary deployment.

---

## Glossaire — Acronymes J4

| Acronyme | Signification | Contexte |
|---|---|---|
| mTLS | mutual Transport Layer Security | TLS bidirectionnel : client et serveur s'authentifient mutuellement |
| TLS | Transport Layer Security | Chiffrement de la couche transport (successor de SSL) |
| CRD | Custom Resource Definition | Extension de l'API K8s — Istio ajoute ses propres types d'objets |
| VS | VirtualService | CRD Istio : règles de routage du trafic (canary, retry, timeout) |
| DR | DestinationRule | CRD Istio : politiques de connexion vers un service (mTLS, pool, circuit breaker) |
| PA | PeerAuthentication | CRD Istio : politique mTLS entre services (STRICT / PERMISSIVE / DISABLE) |
| AP | AuthorizationPolicy | CRD Istio : qui peut appeler qui (niveau L7, par source/destination) |
| GW | Gateway | CRD Istio : point d'entrée du trafic externe vers le mesh |
| HTTP | HyperText Transfer Protocol | Protocole de communication réseau (géré nativement par Istio) |
| gRPC | Google Remote Procedure Call | Protocole RPC binaire sur HTTP/2 — géré par Istio |
| CNCF | Cloud Native Computing Foundation | Organisation qui héberge K8s, Istio, OTel, Prometheus… |
| GKE | Google Kubernetes Engine | Service Kubernetes managé sur Google Cloud Platform |
| CI/CD | Continuous Integration / Continuous Delivery | Pipeline automatisé — canary progressif via VirtualService |
| SA | ServiceAccount | Identité K8s du pod — utilisée par Istio AuthorizationPolicy |
| xDS | Discovery Service APIs | Famille de protocoles Envoy pour la distribution de config (LDS, RDS, CDS…) |

---

> À compléter lors de J4
