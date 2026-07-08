# Examen Ouvert — Helm & Packaging Kubernetes (F4-J2)

> **Format** : questions ouvertes — reformuler avec les termes techniques appropriés.  
> **Ressources autorisées** : `kube-train-chart/`, `notes-J2.md`, `runbook.md`, sorties TP (helm history, kubectl logs)  
> **Objectif** : 6-8 / 10 — les questions avancées ont vocation à générer des corrections instructives.  
> **Réponses** : créer un fichier `corrections/reponse-examen-ouvert-J2.md`, compléter, soumettre à correction.

---

## Questions ⭐ — Fondamentaux

### Question 1 — Anatomie d'un chart ⭐

En lisant `kube-train-chart/` :

a) Lister les fichiers/répertoires **obligatoires** pour qu'un chart soit valide (`helm lint` passe). Parmi les fichiers présents dans `kube-train-chart/`, lesquels seraient optionnels et à quoi servent-ils ?

b) Dans `Chart.yaml`, expliquer la différence entre `version` et `appVersion`. Donner un exemple concret où les deux ne sont pas alignés — et pourquoi c'est normal.

c) Quel est le rôle de `.helmignore` ? Donner deux exemples de fichiers qu'on voudrait exclure d'un chart packagé (`helm package`), et pourquoi.

---

### Question 2 — `fullnameOverride` et nommage des ressources ⭐

En lisant `kube-train-chart/values.yaml` : `fullnameOverride: "kube-train"`

a) Sans ce champ, quel serait le nom généré pour le `Deployment` par le helper `kube-train-chart.fullname` si on installe la release avec `helm upgrade --install kube-train ./kube-train-chart` ? Expliquer la règle de construction du nom.

b) Le nom `kube-train-kube-train-chart` poserait-il un problème technique (au-delà de la lisibilité) ? Penser à la limite DNS de Kubernetes.

c) `fullnameOverride` est une convention Helm. Dans quel cas voudrait-on **ne pas** l'utiliser et laisser Helm construire le nom dynamiquement ?

---

### Question 3 — Hiérarchie des values et deep-merge ⭐

Contexte : on lance la commande :
```bash
helm upgrade kube-train ./kube-train-chart \
  -f kube-train-chart/values-minikube.yaml \
  --set image.tag=v7
```

En lisant `values.yaml` et `values-minikube.yaml` :

a) Donner la valeur finale de `image.tag`, `image.pullPolicy`, `service.type` et `config.gcpProjectId` après résolution. Justifier pour chaque.

b) `values-minikube.yaml` définit `config.springProfilesActive: "postgres"` mais ne redéfinit pas `config.otelServiceName`. Quelle valeur aura `config.otelServiceName` dans le ConfigMap rendu ? Expliquer le mécanisme (deep-merge vs remplacement).

c) `helm get values kube-train` vs `helm get values kube-train --all` : quelle est la différence ? Laquelle correspond à ce que `helm template` utilise pour le rendu ?

---

## Questions ⭐⭐ — Maîtrise

### Question 4 — Go templating : `toYaml`, `nindent`, espaces ⭐⭐

En lisant `kube-train-chart/templates/deployment.yaml`, ligne :
```yaml
resources:
  {{- toYaml .Values.resources | nindent 10 }}
```

a) Décomposer la pipeline `{{- toYaml .Values.resources | nindent 10 }}` : que fait chaque fonction ? Dans quel ordre sont-elles appliquées ?

b) Pourquoi `nindent 10` et pas `nindent 4` ou `nindent 8` ? Comment déterminer la bonne valeur ?

c) Que se passe-t-il si on écrit `{{ toYaml .Values.resources | nindent 10 }}` (sans le `-` gauche) ? Quel serait le YAML rendu incorrect et pourquoi K8s le rejetterait ?

d) Dans `kube-train-chart/templates/configmap.yaml`, la section `data` utilise `{{ .Values.config.trainMessage | quote }}`. Pourquoi `quote` est indispensable ici — donner un exemple de valeur qui casse sans ce filtre.

---

### Question 5 — CronJob : règles CKAD ⭐⭐

En lisant `kube-train-chart/templates/cronjob.yaml` :

a) `restartPolicy: OnFailure` est défini au niveau du pod template du CronJob. Quelles sont les **deux seules valeurs valides** pour `restartPolicy` dans un Job/CronJob, et pourquoi `Always` est-il interdit ?

b) `backoffLimit: 2` : à quel niveau YAML exact est-il défini dans le CronJob ? Dessiner l'arborescence des champs jusqu'à `backoffLimit`. Quelle erreur silencieuse se produit si on le place au mauvais niveau ?

c) `activeDeadlineSeconds: 60` vs `backoffLimit: 2` : expliquer la différence entre ces deux mécanismes d'arrêt. Quel est l'ordre de priorité si les deux conditions sont actives simultanément ?

d) Dans la sortie du TP : `kube-train-outbox-cleanup-29685015-dgj52` — décomposer ce nom. Quelle est la relation entre le CronJob, le Job et le Pod ?

---

### Question 6 — Migration `kubectl` → Helm : ownership ⭐⭐

Lors du TP, la première tentative d'installation a échoué :
```
ConfigMap "kube-train-config" in namespace "default" exists and cannot be imported
label validation error: missing key "app.kubernetes.io/managed-by": must be set to "Helm"
annotation validation error: missing key "meta.helm.sh/release-name": must be set to "kube-train"
```

a) Expliquer pourquoi Helm refuse d'adopter une ressource existante sans ces métadonnées. Quel problème Helm cherche-t-il à éviter ?

b) Deux solutions existent pour résoudre ce conflit. Décrire chacune et indiquer dans quel contexte préférer l'une ou l'autre.

c) Si la migration Helm remplace `k8s/workloads/deployment.yaml` et `k8s/workloads/service.yaml` par le chart, que doit-on faire des fichiers `k8s/postgres-*.yaml`, `k8s/security/rbac.yaml` et `k8s/security/quota.yaml` — les mettre dans le chart ou les garder hors chart ? Justifier.

---

### Question 7 — Conditionnel Cloud SQL Proxy ⭐⭐

En lisant `kube-train-chart/templates/deployment.yaml`, la section :
```yaml
{{- if .Values.cloudSqlProxy.enabled }}
- name: cloud-sql-proxy
  image: {{ .Values.cloudSqlProxy.image }}
  ...
{{- end }}
```

a) Que produit `helm template kube-train ./kube-train-chart -f values-minikube.yaml` pour la section `containers:` — combien de containers ? Expliquer.

b) Que produit `helm template kube-train ./kube-train-chart -f values-gke.yaml` pour la même section — combien de containers ? Quel est l'ordre des containers dans le pod ?

c) En Minikube, le sidecar Cloud SQL Proxy est désactivé. Comment l'API Spring Boot se connecte-t-elle alors à PostgreSQL — quelle différence d'architecture par rapport à GKE ?

d) Pourquoi NE PAS activer le Cloud SQL Proxy en Minikube même si on voulait tester la configuration GKE ? (2 raisons)

---

## Questions ⭐⭐⭐ — Avancé

### Question 8 — Override Spring Boot via variable d'environnement ⭐⭐⭐

Contexte : `application-postgres.properties` contient :
```properties
spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/kube_train
```

Le chart injecte via ConfigMap :
```yaml
SPRING_DATASOURCE_URL: "jdbc:postgresql://postgres-service:5432/postgres"
```

a) Expliquer le mécanisme Spring Boot qui permet à `SPRING_DATASOURCE_URL` (env var) de surcharger `spring.datasource.url` (dans `.properties`). Quel est le nom de ce mécanisme, et quelle est la règle de conversion du nom ?

b) Donner la priorité complète des sources de configuration Spring Boot (au moins 4 niveaux). À quel niveau se situe une variable d'environnement, et à quel niveau se situe un fichier `application-postgres.properties` ?

c) Dans `values-gke.yaml`, `springDatasourceUrl: ""` (vide). Le template `configmap.yaml` contient :
```yaml
{{- if .Values.config.springDatasourceUrl }}
SPRING_DATASOURCE_URL: {{ .Values.config.springDatasourceUrl | quote }}
{{- end }}
```
Expliquer pourquoi une chaîne vide `""` est évaluée comme `false` en Go templates, et ce que cela implique pour la ConfigMap GKE (la clé `SPRING_DATASOURCE_URL` est-elle présente ?). Que se passe-t-il alors pour `spring.datasource.url` sur GKE ?

---

### Question 9 — Analyse d'un `helm history` ⭐⭐⭐

Voici l'historique obtenu en fin de TP :

```
REVISION  STATUS     DESCRIPTION
1         superseded  Install complete
2         superseded  Upgrade complete
3         superseded  Upgrade complete
4         superseded  Upgrade complete
5         superseded  Upgrade complete
6         superseded  Upgrade complete
7         failed      Upgrade "kube-train" failed: resource Deployment/default/kube-train not ready. status: InProgress, message: Pending termination... context deadline exceeded
8         deployed    Rollback to 6
```

a) Reconstituer l'enchaînement des événements de REVISION 6 à REVISION 8. Quelle commande a déclenché chaque révision ?

b) Pourquoi la REVISION 8 est-elle "Rollback to **6**" et non "Rollback to 7" — alors que 7 est la dernière révision connue ?

c) Pendant que la REVISION 7 était `pending-upgrade`, quel était le comportement du service Kubernetes `kube-train` (requêtes entrantes) ? Justifier en vous appuyant sur la stratégie `RollingUpdate` et les deux pods visibles simultanément.

d) `helm rollback kube-train 5` — que ferait cette commande à partir de l'état actuel (REVISION 8 deployed) ? Quelle serait la REVISION 9 dans l'historique ?

---

### Question 10 — Intégration Helm dans le pipeline CI/CD GKE ⭐⭐⭐

Le pipeline GitHub Actions actuel utilise :
```bash
kubectl apply -f k8s/workloads/deployment-gke.yaml
```

On veut le remplacer par Helm.

a) Écrire la commande `helm upgrade` complète pour le déploiement GKE en CI/CD. Elle doit : utiliser le SHA git comme image tag, activer le rollback en cas d'échec, utiliser `values-gke.yaml`, tolérer un démarrage de 5 minutes (Spring Boot 4 + OTel sur Autopilot).

b) `values-gke.yaml` configure `strategy.maxSurge: 0, maxUnavailable: 1`. Expliquer pourquoi cette configuration est préférable aux valeurs par défaut (`maxSurge: 1, maxUnavailable: 0`) spécifiquement pour GKE Autopilot — quel problème les valeurs par défaut causent-elles sur un cluster auto-provisionné ?

c) En cas d'échec du déploiement avec `--rollback-on-failure`, `helm history` montrera deux nouvelles révisions. Décrire leur `STATUS` respectif. Le service reste-t-il disponible pendant le rollback ? Sur quelle version ?

d) Actuellement, `k8s/security/rbac.yaml` (ServiceAccount + Role + RoleBinding) est appliqué séparément. Justifier la décision de le garder **hors** du chart Helm plutôt que de l'inclure dedans — et identifier un scénario où l'inclure dans le chart serait au contraire la bonne décision.
