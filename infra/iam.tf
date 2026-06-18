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

resource "google_service_account_iam_member" "api_workload_identity" {
  service_account_id = google_service_account.kube_train_api.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.project_id}.svc.id.goog[kube-train/kube-train-api-sa]"
}