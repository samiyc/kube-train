# Notes F5-J1 — Core workloads & vitesse kubectl

> Domaine CKAD : Application Design & Build (20 %) + compétence transverse **vitesse**.
> Objectif : ne plus jamais écrire de YAML from scratch — tout générer via l'impératif.

*(Stub — à compléter le jour du drill J1.)*

---

## Setup examen (rappel)

```bash
alias k=kubectl
export do="--dry-run=client -o yaml"
export now="--force --grace-period=0"
source <(kubectl completion bash) && complete -o default -F __start_kubectl k
```

---

## Cheatsheet impératif → YAML (à remplir pendant le drill)

| Besoin | Commande |
|--------|----------|
| Pod simple | `k run nginx --image=nginx $do` |
| Deployment | `k create deployment web --image=nginx --replicas=3 $do` |
| Job | `k create job hello --image=busybox $do -- echo hi` |
| CronJob | `k create cronjob c --image=busybox --schedule="*/1 * * * *" $do -- date` |
| Exposer | `k expose deployment web --port=80 --target-port=8080 $do` |
| Pod + command | `k run p --image=busybox $do -- sleep 3600` |
| Debug éphémère | `k run tmp --rm -it --image=busybox --restart=Never -- sh` |

---

## Points clés à retenir

- (à compléter)

## Blocages rencontrés

- (à compléter)

## Cartes Anki créées

- (à compléter)
