resource "google_service_account" "kube_train_api" {
  account_id   = "kube-train-api-sa"
  display_name = "GSA pour kube-train-api"
}

resource "google_project_iam_member" "api_cloudsql_client" {
  project = var.project_id
  role    = "roles/cloudsql.client"
  member  = "serviceAccount:${google_service_account.kube_train_api.email}"
}

resource "google_project_iam_member" "api_secret_accessor" {
  project = var.project_id
  role    = "roles/secretmanager.secretAccessor"
  member  = "serviceAccount:${google_service_account.kube_train_api.email}"
}

resource "google_project_iam_member" "api_pubsub_publisher" {
  project = var.project_id
  role    = "roles/pubsub.publisher"
  member  = "serviceAccount:${google_service_account.kube_train_api.email}"
}

# namespace default (pod runs in default, not kube-train)
resource "google_service_account_iam_member" "api_workload_identity" {
  service_account_id = google_service_account.kube_train_api.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.project_id}.svc.id.goog[default/kube-train-api-sa]"
}

resource "google_service_account" "notification" {
  account_id   = "notification-sa"
  display_name = "GSA pour notification-service"
}

resource "google_project_iam_member" "notification_pubsub_subscriber" {
  project = var.project_id
  role    = "roles/pubsub.subscriber"
  member  = "serviceAccount:${google_service_account.notification.email}"
}

resource "google_service_account_iam_member" "notification_workload_identity" {
  service_account_id = google_service_account.notification.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.project_id}.svc.id.goog[default/notification-sa]"
}

# ── OTel Collector ────────────────────────────────────────────────────────────
# GSA dédié : le collector exporte les traces (Cloud Trace) et les métriques
# (Cloud Monitoring). Avant, il tournait sur le KSA `default` non annoté, ce qui
# provoquait un PermissionDenied après chaque rebuild du cluster (fix manuel).
# Désormais entièrement déclaratif : GSA + rôles + binding WI ici, annotation du
# KSA dans k8s/observability/otel-collector.yaml.
resource "google_service_account" "otel_collector" {
  account_id   = "otel-collector-sa"
  display_name = "GSA pour l'OTel Collector"
}

# cloudtrace.traces.patch — export des spans vers Cloud Trace
resource "google_project_iam_member" "otel_cloudtrace_agent" {
  project = var.project_id
  role    = "roles/cloudtrace.agent"
  member  = "serviceAccount:${google_service_account.otel_collector.email}"
}

# monitoring.timeSeries.create + metricDescriptors.create — export des métriques
resource "google_project_iam_member" "otel_metric_writer" {
  project = var.project_id
  role    = "roles/monitoring.metricWriter"
  member  = "serviceAccount:${google_service_account.otel_collector.email}"
}

# namespace default (le collector tourne dans default)
resource "google_service_account_iam_member" "otel_workload_identity" {
  service_account_id = google_service_account.otel_collector.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.project_id}.svc.id.goog[default/otel-collector-sa]"
}

# ── CI GitHub Actions ─────────────────────────────────────────────────────────
# Le service account `github-actions-sa`, ses rôles et la fédération Workload
# Identity ont été déplacés dans infra/bootstrap/ (state séparé).
#
# Raison : ils étaient détruits/recréés à chaque cycle destroy/apply. Or le rôle
# artifactregistry.writer est nécessaire au job `build`, qui tourne à chaque push
# même sans cluster — un destroy cassait donc la CI. La couche identité doit
# survivre au cycle de vie de l'infra applicative.
