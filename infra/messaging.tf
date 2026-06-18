resource "google_artifact_registry_repository" "docker" {
  location      = var.region
  repository_id = "kube-train-repo"
  format        = "DOCKER"
  description   = "Images Docker kube-train"
}

resource "google_pubsub_topic" "reservations" {
  name = "train-reservations"
}

resource "google_pubsub_topic" "reservations_dlq" {
  name = "train-reservations-dlq"
}

resource "google_pubsub_subscription" "notification" {
  name  = "notification-subscription"
  topic = google_pubsub_topic.reservations.name

  ack_deadline_seconds       = 20
  message_retention_duration = "604800s"

  dead_letter_policy {
    dead_letter_topic     = google_pubsub_topic.reservations_dlq.id
    max_delivery_attempts = 5
  }
}
