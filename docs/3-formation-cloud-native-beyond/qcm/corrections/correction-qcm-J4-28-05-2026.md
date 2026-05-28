# Correction QCM J4 — Sécurité applicative (OAuth2, NetworkPolicies, Trivy)
> Date : 28/05/2026 | Score : **7 / 8** (87.5 %)

---

## Question 1 — Le triangle OAuth2

**Ta réponse : ✅ CORRECT (1/1)**

> B) Resource Server — valide les tokens JWT et protège les ressources.

`kube-train-api` ne crée pas de tokens (≠ Authorization Server) et ne les obtient pas pour son propre compte (≠ Client). Elle reçoit un JWT dans le header `Authorization` et le valide via JWKS.

---

## Question 2 — Spring Security : Resource Server

**Ta réponse : ✅ CORRECT (1/1)**

> C) `.oauth2ResourceServer(oauth2 -> oauth2.jwt(...))`

Les autres options sont soit obsolètes (`@EnableAuthorizationServer` supprimé en Spring Security 6), soit pour d'autres flows (`.oauth2Login` = Authorization Code Flow côté navigateur, pas Resource Server).

---

## Question 3 — Profile "secured" : stratégie

**Ta réponse : ✅ CORRECT (1/1)**

> B) Deux configurations mutuellement exclusives.

`@Profile("secured")` + `@Profile("!secured")` garantit qu'**exactement un** `SecurityFilterChain` bean est chargé. Sans ce mécanisme, Spring Boot 4 lèverait une erreur d'ambiguïté (deux beans du même type non qualifiés). La nuance : ce n'est pas "dev vs prod" mais "avec ou sans Keycloak disponible", indépendamment de l'environnement.

---

## Question 4 — JWT : structure

**Ta réponse : ✅ CORRECT (1/1)**

> B) Payload (2ème partie).

Rappel : Base64url ≠ chiffrement. Le payload est **lisible par n'importe qui** qui possède le token. La sécurité repose uniquement sur la **signature** (3ème partie). Ne jamais stocker de secrets dans les claims JWT.

```
Header  . Payload                          . Signature
alg/kid   sub/exp/iss/email/preferred_user   RSA(clé privée Keycloak)
```

---

## Question 5 — NetworkPolicy : comportement par défaut

**Ta réponse : ✅ CORRECT (1/1)**

> A) Les pods redémarrent car les liveness probes échouent.

`default-deny-ingress` sans règle allow → les **kubelet probes** (startupProbe, livenessProbe, readinessProbe) sont bloquées car le kubelet envoie ses requêtes depuis l'IP du node (extérieure au cluster). Résultat concret observé dans kube-train :

```
startupProbe fails (50×5s) → CrashLoopBackOff
```

D) aurait été correct si seul le trafic client était bloqué mais pas les probes kubelet (ce n'est pas le cas ici).

---

## Question 6 — NetworkPolicy : trafic kubelet

**Ta réponse : ❌ FAUX (0/1)**

> Ta réponse : A) D'un pod de monitoring dans le namespace `kube-system`  
> Réponse correcte : **B) Des IPs des nodes → nécessite `ipBlock: cidr: 0.0.0.0/0`**

Le **kubelet** est un processus qui tourne **directement sur le node** (pas dans un pod). Il envoie les health probes depuis l'IP du node. Cette IP est extérieure au réseau des pods → elle tombe dans la catégorie `ipBlock`.

```yaml
# Fix appliqué dans k8s/network-policy-api.yaml
- ipBlock:
    cidr: 0.0.0.0/0   # ← autorise kubelet (node IP) + clients externes (LoadBalancer)
```

Le namespace `kube-system` contient CoreDNS, kube-proxy… mais **pas** le kubelet (qui est un démon système, pas un pod).

---

## Question 7 — Trivy : CVE et CI/CD

**Ta réponse : ✅ CORRECT (1/1)**

> B) `continue-on-error: true` + parsing JSON `jq` → `::warning::` + `$GITHUB_STEP_SUMMARY`

C'est exactement l'implémentation dans `.github/workflows/deploy.yml` :

```yaml
- name: Trivy — Scan ...
  uses: aquasecurity/trivy-action@master
  continue-on-error: true          # ← ne bloque pas le pipeline
  with:
    exit-code: '1'
    severity: 'CRITICAL'
    output: 'trivy-api.json'

- name: Trivy — Résumé
  # parse trivy-api.json avec jq
  # → echo "::warning title=..." (annotation GitHub)
  # → echo "..." >> $GITHUB_STEP_SUMMARY (tableau markdown)
```

Le commentaire dans le YAML précise même : *"En prod : retirer `continue-on-error` pour bloquer sur CRITICAL"*. Stratégie actuelle = visibilité sans friction.

---

## Question 8 — Headers OWASP

**Ta réponse : ✅ CORRECT (1/1)**

> C) `X-Frame-Options: DENY`

| Header | Protection |
|---|---|
| `X-Frame-Options: DENY` | **Clickjacking** — interdit l'embedding dans une `<iframe>` |
| `X-Content-Type-Options: nosniff` | MIME sniffing — le navigateur ne "devine" pas le type |
| `Strict-Transport-Security` | HTTPS forcé (HSTS) |
| `X-XSS-Protection: 0` | Désactive le filtre XSS natif (obsolète et dangereux) |

---

## Bilan

| Q | Sujet | Réponse | Résultat |
|---|---|---|---|
| 1 | Triangle OAuth2 | B | ✅ |
| 2 | Spring Resource Server config | C | ✅ |
| 3 | @Profile stratégie | B | ✅ |
| 4 | JWT structure | B | ✅ |
| 5 | NetworkPolicy deny-all | A | ✅ |
| 6 | Source trafic kubelet probes | A → **B** | ❌ |
| 7 | Trivy CI/CD stratégie | B | ✅ |
| 8 | Header anti-clickjacking | C | ✅ |

**Score final : 7/8 — 87.5 %** 🎯

### Point à retenir

> Le kubelet est un **processus système sur le node**, pas un pod. Ses requêtes HTTP (health probes) arrivent depuis l'IP du node, pas depuis un namespace Kubernetes. NetworkPolicy ne voit pas de `namespaceSelector` pour le kubelet → il faut impérativement `ipBlock: cidr: 0.0.0.0/0` pour l'autoriser.
