resource "google_project_service" "trafficdirector" {
  service            = "trafficdirector.googleapis.com"
  disable_on_destroy = false
}

resource "google_project_service" "meshca" {
  service            = "meshca.googleapis.com"
  disable_on_destroy = false
}

resource "google_project_service" "networksecurity" {
  service            = "networksecurity.googleapis.com"
  disable_on_destroy = false
}

resource "google_project_service" "networkservices" {
  service            = "networkservices.googleapis.com"
  disable_on_destroy = false
}
