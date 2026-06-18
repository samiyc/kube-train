data "google_compute_network" "default" {
  name = "default"
}

data "google_compute_subnetwork" "default_euw1" {
  name   = "default"
  region = var.region
}

resource "google_container_cluster" "main" {
  name     = var.cluster_name
  location = var.region

  enable_autopilot = true

  network    = data.google_compute_network.default.self_link
  subnetwork = data.google_compute_subnetwork.default_euw1.self_link

  deletion_protection = false

  workload_identity_config {
    workload_pool = "${var.project_id}.svc.id.goog"
  }

  release_channel {
    channel = "REGULAR"
  }
}