# Audit — Token Bearer JWT / OAuth2

## Verdict court

1. **Oui, le Bearer JWT est encore câblé**, mais il est **dormant par défaut** : la chaîne OAuth2 Resource Server n'est chargée que sous le profil Spring `secured` (`SecurityConfig`, `@Profile("secured")`).  
2. **En production GKE actuelle, il n'est pas actif** : le déploiement lance l'API avec `SPRING_PROFILES_ACTIVE="postgres,gcp"`, donc sans `secured`.  
3. **`GET /secure` n'est pas “Bearer-only”** : le contrôleur vérifie toujours un header `X-API-KEY`; avec le profil `secured`, il faut donc passer **JWT Bearer + X-API-KEY** pour atteindre un `200`.

## Sources vérifiées

- `kube-train-api\src\main\java\com\kubetrain\api\config\SecurityConfig.java` : configuration OAuth2 Resource Server active uniquement sous `secured` (`@Profile("secured")`) et validation JWT via `.oauth2ResourceServer(...jwt...)` (lignes 26-29, 55-56).
- `kube-train-api\src\main\java\com\kubetrain\api\config\PermissiveSecurityConfig.java` : fallback actif quand `secured` n'est pas actif (`@Profile("!secured")`) et `anyRequest().permitAll()` (lignes 20-23, 35).
- `kube-train-api\src\main\java\com\kubetrain\api\controller\TrainController.java` : `/secure` lit `train.api.key`, attend `X-API-KEY`, puis compare la valeur fournie (lignes 50-51, 125-149).
- `kube-train-api\src\main\resources\application-secured.properties` : issuer Keycloak local `http://localhost:8180/realms/kube-train` (lignes 1-10).
- `k8s\workloads\deployment-gke.yaml` : GKE injecte `TRAIN_API_KEY` puis `SPRING_PROFILES_ACTIVE: "postgres,gcp"` (lignes 113-120).
- `kube-train-chart\values-gke.yaml` : l'overlay Helm GKE met aussi `springProfilesActive: "postgres,gcp"` (lignes 47-50).
- `docker-compose.yml` et `keycloak\realm-export.json` : Keycloak local sur `localhost:8180`, realm `kube-train`, client `kube-train-api`, secret `kube-train-secret`, utilisateur `testuser/test123` (docker-compose lignes 73-89 ; realm lignes 1-18, 37-51).

## Tableau de synthèse

| Mécanisme | Endpoint(s) concernés | Profil requis | Actif en prod GKE ? | Comment tester |
|---|---|---:|---:|---|
| **JWT Bearer / OAuth2 Resource Server** | Tout endpoint non explicitement public, notamment `POST /reservations` et `GET /secure` via `.anyRequest().authenticated()` | `secured` | **Non** (`postgres,gcp`, pas `secured`) | Lancer l'API avec `secured`, obtenir un token Keycloak, appeler avec `Authorization: Bearer ...` |
| **JWT Bearer — endpoints publics même sous `secured`** | `GET /`, `GET /trains`, `GET /trains/**`, `GET /reservations/**`, `/actuator/**`, `/swagger-ui/**`, `/v3/api-docs/**` | `secured` possible, mais JWT non requis | Non pertinent | Appel direct sans Bearer : doit passer pour ces routes |
| **X-API-KEY** | `GET /secure` uniquement, dans le contrôleur | Aucun profil Spring requis | **Oui**, car la route existe et GKE fournit `TRAIN_API_KEY` | `curl` avec `X-API-KEY`; sous `secured`, ajouter aussi le Bearer |

> Nuance importante : `SecurityConfig` ne liste pas explicitement `POST /reservations` et `GET /secure` comme matchers protégés ; ils deviennent protégés parce qu'ils ne sont pas dans la whitelist et tombent dans `.anyRequest().authenticated()` (`SecurityConfig.java`, lignes 45-56).

## Les deux “secure” à ne pas confondre

### 1. Sécurité OAuth2/JWT Bearer — optionnelle, profil `secured`

`SecurityConfig` est annoté `@Profile("secured")` et configure l'API comme **Resource Server** : elle valide les JWT avec l'issuer configuré dans `application-secured.properties` (`SecurityConfig.java`, lignes 26-29 et 55-56 ; `application-secured.properties`, lignes 1-10).

Sous ce profil :

- publics : `GET /`, `GET /trains`, `GET /trains/**`, `GET /reservations/**`, `/actuator/**`, `/swagger-ui/**`, `/v3/api-docs/**` (`SecurityConfig.java`, lignes 45-52) ;
- protégés : tout le reste, donc `POST /reservations`, `GET /secure`, et tout futur endpoint non whitelisté (`SecurityConfig.java`, lignes 52-56).

### 2. Sécurité applicative `X-API-KEY` — dans `GET /secure`

Le endpoint `GET /secure` garde sa propre logique applicative :

- propriété injectée : `@Value("${train.api.key:Pas de clé}")` (`TrainController.java`, lignes 50-51) ;
- header attendu : `X-API-KEY` (`TrainController.java`, lignes 125-133) ;
- erreurs si header absent ou incorrect (`TrainController.java`, lignes 135-142) ;
- succès seulement après comparaison avec la clé configurée (`TrainController.java`, lignes 144-149).

Conséquence :

- **sans profil `secured`** : seul `X-API-KEY` protège réellement `/secure`, car `PermissiveSecurityConfig` laisse passer toutes les requêtes (`PermissiveSecurityConfig.java`, lignes 20-23 et 35) ;
- **avec profil `secured`** : Spring Security vérifie d'abord le JWT Bearer, puis le contrôleur vérifie `X-API-KEY`. Pour `/secure`, il faut donc les deux.

## Réalité runtime GKE

Le manifest GKE actuel déclare :

```yaml
SPRING_PROFILES_ACTIVE: "postgres,gcp"
```

Source : `k8s\workloads\deployment-gke.yaml`, lignes 119-120. L'overlay Helm GKE confirme la même valeur : `kube-train-chart\values-gke.yaml`, lignes 47-50.

Donc :

- `SecurityConfig` (`@Profile("secured")`) **n'est pas chargé** ;
- `PermissiveSecurityConfig` (`@Profile("!secured")`) **est chargé** ;
- l'authentification Bearer est **dormante en GKE** ;
- `/secure` reste protégé par `X-API-KEY`, avec la variable `TRAIN_API_KEY` mappée depuis le secret Kubernetes (`deployment-gke.yaml`, lignes 113-118).

## Swagger / OpenAPI

`OpenApiConfig` documente un schéma `bearerAuth` :

- bouton “Authorize” Swagger UI ;
- token endpoint Keycloak local `http://localhost:8180/realms/kube-train/protocol/openid-connect/token` ;
- type HTTP `bearer`, format `JWT`.

Sources : `OpenApiConfig.java`, lignes 23-27 et 46-53.

Cela **documente** le mécanisme Bearer, mais ne l'active pas à lui seul. L'activation réelle dépend du profil `secured`.

## Tests existants

Les tests contrôleur importent explicitement `PermissiveSecurityConfig`, pas `SecurityConfig` (`TrainControllerTest.java`, lignes 3-4 et 36-38). Les tests `/secure` vérifient le header `X-API-KEY` : absence, mauvaise clé, bonne clé par défaut (`TrainControllerTest.java`, lignes 186-218).

Une recherche dans `kube-train-api\src\test\java` montre aussi `BaseContractTest` avec `PermissiveSecurityConfig` (`BaseContractTest.java`, lignes 30-31). Je n'ai pas trouvé de test dédié qui valide un JWT Bearer ou le profil `secured`.

## Activer et tester le Bearer en local

Depuis PowerShell, dans le dépôt :

```powershell
cd C:\DEVDIR\GITHUB\kube-train
docker compose up -d keycloak
```

Keycloak est déclaré dans `docker-compose.yml` sur `localhost:8180`, avec import automatique du realm (`docker-compose.yml`, lignes 73-89). Attendre quelques secondes que le realm soit importé.

Lancer l'API avec le profil `secured` :

```powershell
cd C:\DEVDIR\GITHUB\kube-train\kube-train-api
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=secured"
```

Obtenir un JWT depuis Keycloak :

```powershell
$token = (curl.exe -s -X POST "http://localhost:8180/realms/kube-train/protocol/openid-connect/token" `
  -H "Content-Type: application/x-www-form-urlencoded" `
  -d "grant_type=password" `
  -d "client_id=kube-train-api" `
  -d "client_secret=kube-train-secret" `
  -d "username=testuser" `
  -d "password=test123" | ConvertFrom-Json).access_token
```

Tester que `POST /reservations` exige le Bearer :

```powershell
# Doit être refusé sous profil secured : pas de Authorization Bearer
curl.exe -i -X POST "http://localhost:8080/reservations" `
  -H "Content-Type: application/json" `
  --data '{"passengerName":"Jean Dupont","trainId":"TGV-7042"}'

# Doit passer côté JWT avec un Bearer valide
curl.exe -i -X POST "http://localhost:8080/reservations" `
  -H "Authorization: Bearer $token" `
  -H "Content-Type: application/json" `
  --data '{"passengerName":"Jean Dupont","trainId":"TGV-7042"}'
```

Tester le double verrou de `GET /secure` :

```powershell
# Sous profil secured : Bearer absent, même avec X-API-KEY => refusé par Spring Security
curl.exe -i "http://localhost:8080/secure" -H "X-API-KEY: Pas de clé"

# Bearer présent mais X-API-KEY absent => refusé par le contrôleur
curl.exe -i "http://localhost:8080/secure" -H "Authorization: Bearer $token"

# Bearer + X-API-KEY => OK si train.api.key vaut la valeur par défaut "Pas de clé"
curl.exe -i "http://localhost:8080/secure" `
  -H "Authorization: Bearer $token" `
  -H "X-API-KEY: Pas de clé"
```

## Recommandation courte

Décider explicitement entre deux modèles :

1. **Activer réellement OAuth2/JWT en GKE** : mettre `SPRING_PROFILES_ACTIVE=postgres,gcp,secured`, remplacer l'issuer local par un IdP accessible depuis GKE, ajouter des tests `secured`, et clarifier que `POST /reservations` est protégé par Bearer. C'est le modèle le plus propre si l'objectif est une API production multi-clients.
2. **Assumer `X-API-KEY` pour cette formation** : garder GKE en `postgres,gcp`, mais renommer/documenter `/secure` comme endpoint “clé API” et éviter de présenter Swagger Bearer comme actif en production.

Avis : pour une vraie production, préférer **OAuth2/JWT activé sur GKE**. Pour un TP court et économique, l'état actuel est acceptable, mais il faut le nommer clairement : **Bearer câblé, non utilisé en prod ; `/secure` = X-API-KEY**.
