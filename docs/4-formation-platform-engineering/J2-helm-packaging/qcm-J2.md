# QCM J2 — Helm & Packaging Kubernetes

**8 questions — Durée estimée : 10-15 min**

---

## Question 1 — [Structure d’un chart]

Dans un chart Helm, quel fichier porte les métadonnées du package (nom, version, `appVersion`) et quel dossier contient les manifestes Kubernetes templatisés ?

A) `values.yaml` et `charts/`
B) `Chart.yaml` et `templates/`
C) `_helpers.tpl` et `crds/`
D) `Chart.lock` et `rendered/`

---

## Question 2 — [Go templating]

Quel extrait Helm est correct pour n’afficher `serviceAccountName` que si `serviceAccount.create` vaut `true`, tout en réutilisant un helper défini dans `_helpers.tpl` ?

A) `{{ if .Values.serviceAccount.create }}serviceAccountName: {{ include "kube-train.serviceAccountName" . }}{{ end }}`
B) `{{ .if Values.serviceAccount.create }}serviceAccountName: {{ template "kube-train.serviceAccountName" }}{{ end }}`
C) `{{ if Values.serviceAccount.create }}serviceAccountName: {{ include kube-train.serviceAccountName . }}{{ end }}`
D) `{{ range .Values.serviceAccount.create }}serviceAccountName: {{ include "kube-train.serviceAccountName" . }}{{ end }}`

---

## Question 3 — [Release lifecycle]

Que fait principalement `helm upgrade --install kube-train ./kube-train-chart --atomic` ?

A) Il force Helm à supprimer l’historique des releases précédentes.
B) Il exécute uniquement un rendu local sans parler au cluster.
C) Il remplace automatiquement toutes les valeurs par celles de `values.yaml`.
D) Il attend que l’upgrade réussisse et effectue un rollback automatique si l’opération échoue.

---

## Question 4 — [Override de valeurs]

Le chart définit `image.tag: latest` dans `values.yaml`. On lance ensuite :

```bash
helm upgrade --install kube-train ./kube-train-chart -f values-gke.yaml --set image.tag=sha-8672f7b
```

Si `values-gke.yaml` contient déjà `image.tag: stable`, quelle valeur sera utilisée au final ?

A) `latest`, car le fichier principal a toujours priorité
B) `stable`, car `-f` écrase toujours `--set`
C) `sha-8672f7b`, car `--set` a la priorité la plus haute
D) Helm échoue car `image.tag` est défini deux fois

---

## Question 5 — [Hooks Helm]

Quelle annotation Helm faut-il poser sur un `Job` pour l’exécuter avant l’installation initiale puis après chaque upgrade ?

A) `helm.sh/hook: pre-install`
B) `helm.sh/hook: pre-install,post-install`
C) `helm.sh/hook: pre-install,post-upgrade`
D) `helm.sh/hook-weight: pre-install,post-upgrade`

---

## Question 6 — [Jobs vs CronJobs]

Quelle affirmation est correcte à propos des `Job` et `CronJob` Kubernetes ?

A) Un `Job` possède obligatoirement un champ `schedule`, alors qu’un `CronJob` utilise `completions`.
B) Un `CronJob` embarque un template de `Job` dans `jobTemplate`, avec un `schedule` au format cron ; `restartPolicy` reste défini dans le template de pod.
C) `restartPolicy: Always` est recommandé dans un `Job` pour relancer le conteneur jusqu’au succès.
D) `backoffLimit` n’existe que pour les `CronJob`, pas pour les `Job`.

---

## Question 7 — [Rendu et simulation]

Quelle différence décrit le mieux `helm template` par rapport à `helm install --dry-run --debug` ?

A) `helm template` rend les manifestes localement sans créer de release ; `helm install --dry-run --debug` simule une installation avec le contexte de release et affiche aussi les notes.
B) `helm template` installe les CRDs, alors que `helm install --dry-run --debug` les ignore.
C) `helm template` nécessite un cluster actif, alors que `helm install --dry-run --debug` fonctionne hors ligne.
D) Les deux commandes créent une release, mais seule la seconde la supprime ensuite.

---

## Question 8 — [ArgoCD + Helm]

Dans ArgoCD, comment déclare-t-on correctement un chart Helm stocké dans un dépôt Git, avec un fichier d’override `values-gke.yaml` ?

A) En déclarant `spec.source.directory.recurse: true` uniquement ; ArgoCD détecte automatiquement Helm.
B) En utilisant `spec.source.chart: kube-train-chart` même si la source est un dépôt Git contenant le chart.
C) En pointant `spec.source.path` vers le dossier du chart et en ajoutant `spec.source.helm.valueFiles`.
D) En mettant `valueFiles` dans `spec.destination` pour que le cluster choisisse les bonnes valeurs.

---

