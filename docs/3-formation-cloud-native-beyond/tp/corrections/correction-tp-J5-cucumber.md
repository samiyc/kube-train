# Correction TP J5 — Cucumber BDD

**Date** : 29/05/2026 | **Formation** : F3-J5 | **Score : ✅ 3/3 scénarios passés (100%)**

---

## 🏆 Bilan

Tous les scénarios Cucumber sont verts :

```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

| Scénario | Résultat |
|----------|----------|
| Réservation réussie (TGV-7042 → 201 CONFIRMED) | ✅ PASSED |
| Train inexistant (FAKE-9999 → 404) | ✅ PASSED |
| Consulter la liste des trains (→ 200, 3 trains) | ✅ PASSED |

---

## 🐛 Problèmes rencontrés (et leur résolution)

### 1. Fichiers dans `src/main/` au lieu de `src/test/`

**Erreur :** `package does not exist` sur toutes les dépendances Cucumber/MockMvc.

**Cause :** Les dépendances Cucumber sont en `scope test` → elles ne sont disponibles
qu'au moment du `testCompile`, pas du `compile`. Placer les fichiers dans `src/main/`
les soumet à la phase `compile` où ces classes sont absentes du classpath.

**Règle :** Tout fichier de test va dans `src/test/java/` sans exception.

---

### 2. Spring Boot 4 — package `@AutoConfigureMockMvc` changé

**Erreur :** `cannot find symbol` sur `@AutoConfigureMockMvc`.

**Cause :** Spring Boot 4 a restructuré les packages de test MVC :
- SB 3.x : `org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc`
- SB 4.x : `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`  ← ici

**Indice :** Pour trouver le bon package en SB4, regarder un test existant comme
`TrainControllerTest.java` qui utilise `@WebMvcTest` — même package racine.

---

### 3. Dépendance `junit-platform-suite` manquante

**Erreur :** `@Suite` non reconnu à la compilation.

**Cause :** Les annotations `@Suite`, `@IncludeEngines`, `@SelectClasspathResource`
viennent de `junit-platform-suite`, pas inclus par le parent Spring Boot.
Il faut l'ajouter explicitement.

```xml
<dependency>
    <groupId>org.junit.platform</groupId>
    <artifactId>junit-platform-suite</artifactId>
    <scope>test</scope>
</dependency>
```

---

### 4. `@SelectPackages` ne découvre pas les scénarios Cucumber

**Erreur :** `NoTestsDiscovered Suite [...] did not discover any tests`

**Cause :** `@SelectPackages("com.kubetrain.api.bdd")` demande à JUnit de chercher
des **classes de test JUnit** dans ce package. Cucumber ne dépose pas de classes là —
ses tests viennent des fichiers `.feature`.

**Fix :** Utiliser `@SelectClasspathResource("features")` qui pointe directement
vers le dossier des `.feature` dans le classpath.

```java
// ❌ Incorrect
@SelectPackages("com.kubetrain.api.bdd")

// ✅ Correct
@SelectClasspathResource("features")
```

---

### 5. Encodage Windows — BOM UTF-8 + `@Etant_donné`

**Erreur :** `illegal character: '\ufeff'` puis `cannot find symbol: class Etant_donnÚ`

**Cause :** Double problème d'encodage sur Windows :

1. **BOM (Byte Order Mark)** : `New-Object System.Text.UTF8Encoding` (sans `$false`) écrit
   les bytes `EF BB BF` en début de fichier. Java rejette le BOM.
   Fix : `New-Object System.Text.UTF8Encoding $false` (paramètre = `encoderShouldEmitUTF8Identifier`)

2. **`@Etant_donné`** : le `é` (classe Java dans `io.cucumber.java.fr`) est sensible à
   l'encodage source. Si le fichier est sauvegardé en Windows-1252, le compilateur
   voit `0xE9` (ANSI) au lieu de `0xC3 0xA9` (UTF-8) → nom de classe corrompu.

**Solution retenue (pragmatique) :** Utiliser les annotations anglaises `io.cucumber.java.en.*`
qui n'ont pas d'accents dans les noms de classes. Les **textes des étapes restent en français**
(Cucumber matche le texte, pas le mot-clé).

```java
// ❌ Fragile sur Windows
import io.cucumber.java.fr.Etant_donné;
@Etant_donné("le train ...")

// ✅ Robuste sur Windows
import io.cucumber.java.en.Given;
@Given("le train ...")      // le texte de l'étape est en français dans le .feature
```

> **À retenir :** sur Windows, toujours écrire les fichiers Java sensibles avec
> `New-Object System.Text.UTF8Encoding $false` en PowerShell. En IntelliJ :
> Settings → Editor → File Encodings → tout mettre en UTF-8 + "BOM for new UTF-8 files: No BOM".

---

## 📁 Version officielle

### `reservations.feature`

Améliorations vs la version TP :
- Descriptions des scénarios plus claires
- Ajout du **Scenario Outline** (bonus) : valide les 3 trains + 1 train inexistant

```gherkin
Feature: Reservations et consultation des trains
  Tests BDD pour les endpoints /reservations et /trains.
  Les donnees de trains sont en memoire (TrainService.TRAINS).

  Scenario: Reservation reussie sur un train existant
    Given le train "TGV-7042" existe avec des places disponibles
    When je reserve un billet pour "Jean Dupont" sur le train "TGV-7042"
    Then la reponse HTTP est 201
    And la reservation a le statut "CONFIRMED"
    And le prix est de 29.90

  Scenario: Reservation echouee - train inexistant
    When je reserve un billet pour "Jean Dupont" sur le train "FAKE-9999"
    Then la reponse HTTP est 404

  Scenario: Consulter la liste des trains
    When je consulte la liste des trains
    Then la reponse HTTP est 200
    And la liste contient 3 trains

  Scenario Outline: Validation du statut HTTP par identifiant de train
    When je reserve un billet pour "Passager Test" sur le train "<trainId>"
    Then la reponse HTTP est <status>

    Examples:
      | trainId  | status |
      | TGV-7042 | 201    |
      | TER-2814 | 201    |
      | IC-6734  | 201    |
      | FAKE-9999| 404    |
```

---

### `ReservationSteps.java`

Amélioration clé : le `@Given` ne stocke plus dans `resultActions` (le Given vérifie
l'état sans polluer le résultat partagé qui sera lu par le `@Then`).

```java
@Given("le train {string} existe avec des places disponibles")
public void leTrainExisteAvecDesPlaces(String trainId) throws Exception {
    // Vérifie que le train existe — NE stocke PAS dans resultActions
    mockMvc.perform(get("/trains/{id}", trainId))
           .andExpect(status().isOk());
}
```

---

## 💡 Points clés à retenir

### Architecture BDD

```
.feature (Gherkin)          →  qui lit :   PO, QA, Dev
Step Definitions (Java)     →  qui fait :  appels MockMvc
Application (Controller)    →  sous test : logique métier réelle
```

- Un `.feature` = une **fonctionnalité métier** (pas une classe Java)
- Un `Scenario` = un **cas d'usage** concret (pas un test unitaire)
- Les steps se réutilisent entre scénarios : `"la reponse HTTP est {int}"` sert pour 201 ET 404

### `Scenario Outline` = template de scénario

```gherkin
Scenario Outline: Titre du template
  When je fais <action>
  Then le résultat est <attendu>

  Examples:
    | action | attendu |
    | A      | X       |
    | B      | Y       |
```
→ Génère automatiquement 2 scénarios distincts. Pratique pour tester de nombreux cas
sans dupliquer les steps.

### Comparaison Cucumber vs JUnit classique

| Critère | JUnit (`@Test`) | Cucumber (`.feature`) |
|---------|-----------------|----------------------|
| Lisible par le PO | ❌ Non | ✅ Oui |
| Proche du code | ✅ Direct | Indirect (via steps) |
| Paramétrage | `@ParameterizedTest` | `Scenario Outline` |
| Overhead setup | Faible | Moyen (Spring context) |
| Idéal pour | Tests unitaires/intégration | Tests comportementaux E2E |

### Quand utiliser Cucumber ?

✅ **Oui** : APIs métier complexes avec règles lisibles par des non-devs,
pipelines de validation avec QA/PO, documentation vivante des comportements.

❌ **Non** : Tests unitaires de services/repositories (overhead inutile),
tests de performance (trop lent), projets sans PO impliqué.

---

## 🏅 Évaluation détaillée

| Critère | Points | Obtenu |
|---------|--------|--------|
| 3 scénarios implémentés et verts | 6 | 6 |
| MockMvc patterns corrects (POST body, jsonPath) | 2 | 2 |
| Structure BDD respectée (Given/When/Then séparés) | 1 | 1 |
| État `resultActions` partagé entre étapes | 1 | 1 |
| **Total** | **10** | **10** |

**Points bonus :** Avoir persisté malgré 5 erreurs successives de natures différentes
(classpath, SB4 API, JUnit discovery, BOM, encodage). Chaque erreur correspondait
à un concept distinct — tu les connais maintenant. 💪
