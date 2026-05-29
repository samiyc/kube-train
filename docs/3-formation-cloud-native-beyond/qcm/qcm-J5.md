# QCM J5 — Qualité & Sécurité (BDD, SonarCloud, Trivy, CVE)

**8 questions — Durée estimée : 10-15 min**

---

## Question 1 — BDD : rôle du fichier `.feature`

Dans une approche Cucumber BDD, à quoi sert le fichier `.feature` ?

A) C'est un fichier de configuration Spring qui active les fonctionnalités  
B) C'est une spécification en langage naturel (Gherkin) lisible par le PO, liée à des step definitions Java  
C) C'est un fichier de test JUnit classique avec une extension différente  
D) C'est un fichier Docker qui déclare les fonctionnalités de l'image  

---

## Question 2 — Cucumber : Scenario Outline

Quel est l'avantage d'un `Scenario Outline` avec une table `Examples` par rapport à plusieurs `Scenario` séparés ?

A) Il est plus rapide à l'exécution car il compile le code une seule fois  
B) Il permet de tester N combinaisons avec un seul template paramétré, évitant la duplication  
C) Il est obligatoire pour les tests d'intégration Spring Boot  
D) Il génère automatiquement les step definitions Java  

---

## Question 3 — JaCoCo vs SonarCloud

Quelle affirmation décrit correctement la relation entre JaCoCo et SonarCloud ?

A) JaCoCo remplace SonarCloud — un seul des deux est nécessaire  
B) SonarCloud génère le rapport de couverture, JaCoCo l'affiche dans un dashboard  
C) JaCoCo instrumente les tests et génère `jacoco.xml` ; SonarCloud le lit et l'intègre dans le Quality Gate  
D) Les deux outils scannent les images Docker pour détecter des vulnérabilités  

---

## Question 4 — Quality Gate : seuils

Sur SonarCloud (plan gratuit), le Quality Gate "Sonar way" applique un seuil de couverture sur le "New Code". Quelle stratégie a-t-on utilisée pour passer la Quality Gate avec 83% ?

A) On a désactivé la vérification de couverture  
B) On a utilisé `sonar.coverage.exclusions` pour exclure les classes infra (config, entity, DTO) sans logique métier testable  
C) On a ajouté des tests vides pour augmenter artificiellement le coverage  
D) On a basculé sur un Quality Gate custom avec un seuil à 50%  

---

## Question 5 — Trivy vs SonarCloud

Quelle est la différence fondamentale entre Trivy et SonarCloud en termes de périmètre d'analyse ?

A) Trivy analyse le code source, SonarCloud analyse les images Docker  
B) Trivy scanne l'image Docker (dépendances JAR + paquets OS) pour les CVE connues ; SonarCloud analyse le code source (bugs, smells, coverage)  
C) Les deux font exactement la même chose mais Trivy est gratuit  
D) SonarCloud détecte les CVE, Trivy mesure la couverture de tests  

---

## Question 6 — CVE : stratégie de fix

Une CVE CRITICAL est détectée sur `tomcat-embed-core:11.0.21` (version gérée par le BOM Spring Boot 4.0.6). Comment la corriger ?

A) Modifier directement le fichier JAR dans le conteneur Docker  
B) Ajouter `<tomcat.version>11.0.22</tomcat.version>` dans les `<properties>` du pom.xml pour override le BOM  
C) Supprimer Tomcat et utiliser Jetty à la place  
D) Attendre la prochaine version de Spring Boot — on ne peut rien faire  

---

## Question 7 — Sécurité Kubernetes : `automountServiceAccountToken`

Pourquoi mettre `automountServiceAccountToken: false` sur les pods en production ?

A) Ça accélère le démarrage du pod en évitant le montage d'un volume  
B) Ça réduit la surface d'attaque : si le pod est compromis, l'attaquant n'a pas accès au token K8s qui permet d'appeler l'API server  
C) C'est obligatoire sur GKE Autopilot, sinon le pod ne démarre pas  
D) Ça empêche le pod de communiquer avec les autres pods du cluster  

---

## Question 8 — Pipeline CI/CD : permissions

Dans GitHub Actions, pourquoi déclarer les permissions au niveau de chaque job plutôt qu'au niveau du workflow ?

A) C'est une obligation GitHub — les permissions workflow-level ne fonctionnent plus  
B) Principe du moindre privilège : chaque job n'obtient que les droits dont il a besoin (ex: `test` n'a pas besoin de `id-token: write`)  
C) Ça permet d'exécuter les jobs en parallèle  
D) C'est purement cosmétique, ça n'a aucun impact sur la sécurité  
