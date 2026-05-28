# TP J5 — Cucumber BDD : Tests comportementaux avec Gherkin

**Durée estimée : 45-60 min** | **Difficulté : ⭐⭐ (guidé)**

---

## 🎯 Objectif

Ajouter des tests **BDD (Behavior-Driven Development)** à `kube-train-api` avec Cucumber.
Ces tests décrivent le comportement de l'API en langage naturel (Gherkin), puis sont exécutés comme des tests JUnit classiques.

---

## 📚 Théorie express — C'est quoi Cucumber ?

### Le problème que Cucumber résout

Les tests JUnit classiques (`TrainServiceTest.java`) sont écrits **par et pour les développeurs**.
Un Product Owner ou un QA ne peut pas les lire pour vérifier que le comportement correspond au besoin métier.

### La solution : BDD (Behavior-Driven Development)

On écrit d'abord le **comportement attendu** en français (ou anglais), dans un fichier `.feature` :

```gherkin
Fonctionnalité: Réservation de trains

  Scénario: Réserver un billet sur un train existant
    Étant donné le train "TGV-7042" de "Paris" vers "Lille" à 29.90€
    Quand je réserve un billet pour "Jean Dupont" sur le train "TGV-7042"
    Alors la réservation est confirmée
    Et le prix est de 29.90€
```

Puis on implémente des **Step Definitions** Java qui font le lien entre chaque phrase et du code exécutable.

### Architecture Cucumber

```
┌─────────────────────────────────────────────────────┐
│  .feature (Gherkin)                                 │
│  → Langage naturel = spécification vivante          │
│  → Lisible par le PO / QA / dev                     │
└────────────────────┬────────────────────────────────┘
                     │ exécuté par
                     ▼
┌─────────────────────────────────────────────────────┐
│  Step Definitions (Java)                            │
│  → @Étant_donné("le train {string}...")             │
│  → @Quand("je réserve un billet...")                │
│  → @Alors("la réservation est confirmée")           │
│  → Chaque phrase du .feature a un match ici         │
└────────────────────┬────────────────────────────────┘
                     │ appelle
                     ▼
┌─────────────────────────────────────────────────────┐
│  Code applicatif (Controller, Service, MockMvc)     │
│  → On teste l'API "de l'extérieur" via HTTP         │
│  → Comme un utilisateur réel le ferait              │
└─────────────────────────────────────────────────────┘
```

### Vocabulaire

| Terme | Signification |
|-------|---------------|
| **Feature** | Fichier `.feature` décrivant une fonctionnalité métier |
| **Scenario** | Un cas de test concret (1 feature peut avoir N scénarios) |
| **Given / When / Then** | Étapes du scénario (précondition / action / vérification) |
| **Step Definition** | Méthode Java annotée qui implémente une étape |
| **Glue** | Le package Java où Cucumber cherche les Step Definitions |
| **Scenario Outline** | Template de scénario avec des exemples tabulés (paramétrage) |

---

## 🛠️ Étape 1 — Setup Maven (5 min)

### 1.1 — Ajouter les dépendances dans `kube-train-api/pom.xml`

Ajoute ces 3 dépendances dans le bloc `<dependencies>` (scope `test`) :

```xml
<!-- Cucumber BDD : tests comportementaux Gherkin -->
<dependency>
    <groupId>io.cucumber</groupId>
    <artifactId>cucumber-java</artifactId>
    <version>7.22.0</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>io.cucumber</groupId>
    <artifactId>cucumber-spring</artifactId>
    <version>7.22.0</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>io.cucumber</groupId>
    <artifactId>cucumber-junit-platform-engine</artifactId>
    <version>7.22.0</version>
    <scope>test</scope>
</dependency>
```

**Explications :**
- `cucumber-java` — le cœur : annotations `@Given`, `@When`, `@Then`, parsing Gherkin
- `cucumber-spring` — intégration Spring : permet d'injecter les beans Spring dans les steps (ex: `MockMvc`)
- `cucumber-junit-platform-engine` — permet à Maven Surefire (JUnit Platform) de découvrir et exécuter les tests Cucumber

### 1.2 — Créer le dossier des features

Cucumber cherche les `.feature` dans `src/test/resources/features/` par convention.

```
kube-train-api/
└── src/test/
    ├── java/com/kubetrain/api/
    │   └── bdd/                    ← Step Definitions (à créer)
    │       ├── CucumberConfig.java
    │       └── ReservationSteps.java
    └── resources/
        └── features/               ← Fichiers Gherkin (à créer)
            └── reservations.feature
```

**Action** : Créer les dossiers :
- `src/test/resources/features/`
- `src/test/java/com/kubetrain/api/bdd/`

---

## ✍️ Étape 2 — Écrire ton premier fichier `.feature` (15 min)

### 2.1 — Créer `src/test/resources/features/reservations.feature`

Écris un fichier Gherkin **en français** avec les scénarios suivants :

**Scénario 1 — Réservation réussie**
- Précondition : le train "TGV-7042" existe avec des places disponibles
- Action : on POST une réservation pour "Jean Dupont" sur "TGV-7042"
- Vérification : la réponse est HTTP 201, le status est "CONFIRMED", le prix est 29.90€

**Scénario 2 — Train inexistant**
- Précondition : aucune (on va cibler un train qui n'existe pas)
- Action : on POST une réservation sur "FAKE-9999"
- Vérification : la réponse est HTTP 404

**Scénario 3 — Consulter la liste des trains**
- Précondition : des trains existent dans le système
- Action : on GET /trains
- Vérification : la réponse contient 3 trains

### Syntaxe Gherkin à utiliser

```gherkin
# language: fr
Fonctionnalité: Titre de la fonctionnalité
  Description optionnelle sur plusieurs lignes.

  Scénario: Nom du scénario
    Étant donné <précondition>
    Quand <action>
    Alors <vérification>
    Et <vérification supplémentaire>
```

**Indices :**
- Le commentaire `# language: fr` en première ligne active les mots-clés français
- Les valeurs entre guillemets (`"TGV-7042"`) deviennent des paramètres `{string}` dans les steps
- Les nombres (`29.90`) deviennent des paramètres `{double}` ou `{int}`
- `Et` est un synonyme de `Alors` (pour chaîner les vérifications)

### ⚠️ Piège courant
Ne mets PAS de logique Java dans le `.feature`. C'est du langage métier. Par exemple :
- ❌ `Quand je fais un POST sur /reservations avec le body {"passengerName":"Jean"}`
- ✅ `Quand je réserve un billet pour "Jean Dupont" sur le train "TGV-7042"`

---

## ⚙️ Étape 3 — Configuration Cucumber-Spring (10 min)

### 3.1 — Créer `CucumberConfig.java`

Ce fichier configure l'intégration Cucumber + Spring Boot Test.

Emplacement : `src/test/java/com/kubetrain/api/bdd/CucumberConfig.java`

```java
package com.kubetrain.api.bdd;

import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

@CucumberContextConfiguration
@SpringBootTest
@AutoConfigureMockMvc
public class CucumberConfig {
    // Pas de code nécessaire — les annotations suffisent
    // Cucumber utilise cette classe pour démarrer le contexte Spring Boot Test
}
```

**Explication ligne par ligne :**
- `@CucumberContextConfiguration` — dit à Cucumber : "utilise cette classe pour créer le contexte Spring"
- `@SpringBootTest` — démarre l'application Spring Boot complète (pas juste un slice)
- `@AutoConfigureMockMvc` — injecte un `MockMvc` prêt à l'emploi pour tester les endpoints HTTP sans serveur réel

### 3.2 — Créer le Runner JUnit Platform

Créer le fichier `src/test/java/com/kubetrain/api/bdd/RunCucumberTest.java` :

```java
package com.kubetrain.api.bdd;

import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

@Suite
@IncludeEngines("cucumber")
@SelectPackages("com.kubetrain.api.bdd")
public class RunCucumberTest {
    // Runner JUnit Platform pour Cucumber
    // Maven Surefire découvre cette classe et exécute les .feature
}
```

### 3.3 — Fichier de configuration Cucumber

Créer `src/test/resources/cucumber.properties` :

```properties
cucumber.glue=com.kubetrain.api.bdd
cucumber.features=classpath:features
cucumber.plugin=pretty
```

**Explication :**
- `glue` — le package Java où Cucumber cherche les `@Given/@When/@Then`
- `features` — le dossier des fichiers `.feature`
- `plugin=pretty` — affiche les scénarios dans la console Maven de façon lisible

---

## 🧩 Étape 4 — Implémenter les Step Definitions (20 min)

### 4.1 — Créer `ReservationSteps.java`

Emplacement : `src/test/java/com/kubetrain/api/bdd/ReservationSteps.java`

C'est ici que tu fais le lien entre chaque phrase Gherkin et du code Java.

**Principe :**
- Chaque phrase du `.feature` doit avoir un `@Étant_donné` / `@Quand` / `@Alors` correspondant
- Cucumber matche les phrases via des **expressions** (regex ou Cucumber Expressions)
- Les paramètres `{string}`, `{int}`, `{double}` sont extraits automatiquement

**Squelette de départ (à compléter) :**

```java
package com.kubetrain.api.bdd;

import io.cucumber.java.fr.Alors;
import io.cucumber.java.fr.Et;
import io.cucumber.java.fr.Etant_donné;
import io.cucumber.java.fr.Quand;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class ReservationSteps {

    @Autowired
    private MockMvc mockMvc;

    private ResultActions resultActions;

    // ==================== GIVEN ====================

    @Etant_donné("le train {string} existe avec des places disponibles")
    public void leTrainExisteAvecDesPlaces(String trainId) {
        // Le train TGV-7042 est en mémoire statique (TRAINS map dans TrainService)
        // → Pas besoin de setup, c'est un Given "passif" (précondition toujours vraie)
        // On peut juste vérifier que le train existe
        // TODO : à toi de compléter
    }

    // ==================== WHEN ====================

    @Quand("je réserve un billet pour {string} sur le train {string}")
    public void jeReserveUnBillet(String passengerName, String trainId) throws Exception {
        // TODO : faire un POST /reservations avec MockMvc
        // Body JSON : {"passengerName": "...", "trainId": "..."}
        // Stocker le résultat dans this.resultActions
    }

    // ==================== THEN ====================

    @Alors("la réponse HTTP est {int}")
    public void laReponseHTTPEst(int statusCode) throws Exception {
        // TODO : vérifier le status code
        // Indice : resultActions.andExpect(status().is(statusCode))
    }

    @Et("la réservation a le statut {string}")
    public void laReservationALeStatut(String expectedStatus) throws Exception {
        // TODO : vérifier le champ "status" dans le body JSON
        // Indice : resultActions.andExpect(jsonPath("$.status").value(expectedStatus))
    }

    @Et("le prix est de {double}€")
    public void lePrixEstDe(double expectedPrice) throws Exception {
        // TODO : vérifier le champ "price" dans le body JSON
    }
}
```

### 4.2 — Ce que tu dois implémenter toi-même

1. **Compléter le `@Quand`** — construire le JSON et faire le POST avec MockMvc
2. **Compléter les `@Alors`** — vérifier status code, champs JSON
3. **Ajouter les steps manquants** pour tes scénarios 2 et 3 (train inexistant, liste des trains)

### Aide MockMvc — patterns à connaître

```java
// POST avec body JSON
mockMvc.perform(post("/reservations")
    .contentType(MediaType.APPLICATION_JSON)
    .content("""
        {"passengerName": "%s", "trainId": "%s"}
        """.formatted(passengerName, trainId)))

// GET simple
mockMvc.perform(get("/trains"))

// Vérifications
.andExpect(status().isCreated())                    // 201
.andExpect(status().isNotFound())                   // 404
.andExpect(jsonPath("$.status").value("CONFIRMED"))
.andExpect(jsonPath("$.price").value(29.90))
.andExpect(jsonPath("$.length()").value(3))         // taille d'un tableau JSON
```

---

## ▶️ Étape 5 — Exécuter les tests (5 min)

### Depuis IntelliJ

Clic droit sur `RunCucumberTest.java` → Run. Tu verras les scénarios s'afficher en vert/rouge.

### Depuis Maven (WSL ou PowerShell)

```bash
cd kube-train-api
./mvnw test -Dtest=RunCucumberTest
```

### Résultat attendu

```
Fonctionnalité: Réservation de trains

  Scénario: Réserver un billet sur un train existant    ✅ PASSED
    Étant donné le train "TGV-7042" existe avec des places disponibles
    Quand je réserve un billet pour "Jean Dupont" sur le train "TGV-7042"
    Alors la réponse HTTP est 201
    Et la réservation a le statut "CONFIRMED"
    Et le prix est de 29.90€

  Scénario: Réserver sur un train inexistant            ✅ PASSED
    Quand je réserve un billet pour "Marie" sur le train "FAKE-9999"
    Alors la réponse HTTP est 404

  Scénario: Consulter la liste des trains               ✅ PASSED
    Quand je consulte la liste des trains
    Alors la réponse HTTP est 200
    Et la liste contient 3 trains
```

---

## 📋 Checklist de rendu

Quand tu as fini, vérifie ces points :

- [ ] `pom.xml` : 3 dépendances Cucumber ajoutées (scope test)
- [ ] `src/test/resources/features/reservations.feature` : 3 scénarios en Gherkin français
- [ ] `src/test/java/com/kubetrain/api/bdd/CucumberConfig.java` : config Spring Boot Test
- [ ] `src/test/java/com/kubetrain/api/bdd/RunCucumberTest.java` : runner JUnit Platform
- [ ] `src/test/resources/cucumber.properties` : configuration Cucumber
- [ ] `src/test/java/com/kubetrain/api/bdd/ReservationSteps.java` : step definitions complètes
- [ ] `./mvnw test -Dtest=RunCucumberTest` : ✅ BUILD SUCCESS

---

## 💡 Conseils si tu bloques

1. **"Undefined step"** dans la console → Cucumber ne trouve pas le `@Quand` correspondant.
   Vérifie que la phrase dans le `.feature` matche **exactement** l'expression dans l'annotation Java.

2. **"No beans of type MockMvc"** → `@AutoConfigureMockMvc` manquant sur `CucumberConfig`.

3. **"Glue not found"** → vérifie `cucumber.properties` : `cucumber.glue=com.kubetrain.api.bdd`.

4. **Erreur Base de données** → Les tests Cucumber chargent tout le contexte Spring.
   Comme on n'a pas de PostgreSQL en test, il faut que le test utilise le profil **default** (H2/mémoire).
   Solution : ajouter `@ActiveProfiles("default")` sur `CucumberConfig` (ou ne pas activer `postgres`).
   **Alternative** : ajouter une dépendance H2 en scope test + un `application-test.properties` avec une datasource H2.

5. **Accents dans les annotations** — `@Étant_donné` vs `@Etant_donné` : attention, Cucumber Java utilise
   `@Etant_donné` (sans accent sur le É). Vérifie les imports `io.cucumber.java.fr.*`.

---

## 🏆 Bonus (optionnel)

Si tu finis en avance :

- **Ajouter un Scenario Outline** (scénario paramétré) pour tester plusieurs trains :
  ```gherkin
  Plan du Scénario: Réserver différents trains
    Quand je réserve un billet pour "Test" sur le train "<trainId>"
    Alors la réponse HTTP est <status>
    Et le prix est de <prix>€

    Exemples:
      | trainId  | status | prix  |
      | TGV-7042 | 201    | 29.90 |
      | TER-2814 | 201    | 15.50 |
      | IC-6734  | 201    | 22.00 |
      | FAKE-999 | 404    | 0     |
  ```

- **Ajouter un scénario pour `/secure`** avec X-API-KEY valide/invalide

---

*Quand tu as terminé, envoie-moi le résultat de `./mvnw test -Dtest=RunCucumberTest` et tes fichiers. Je corrigerai et te donnerai la version "officielle".*
