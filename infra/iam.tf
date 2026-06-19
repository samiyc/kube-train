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

resource "google_project_iam_member" "github_actions_container_admin" {
  project = var.project_id
  role    = "roles/container.admin"
  member  = "serviceAccount:github-actions-sa@${var.project_id}.iam.gserviceaccount.com"
}

resource "google_project_iam_member" "github_actions_secret_accessor" {
  project = var.project_id
  role    = "roles/secretmanager.secretAccessor"
  member  = "serviceAccount:github-actions-sa@${var.project_id}.iam.gserviceaccount.com"
}
