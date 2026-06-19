variable "db_password" {
  type      = string
  sensitive = true
}

resource "google_sql_database_instance" "main" {
  name             = "kube-train-db"
  region           = var.region
  database_version = "POSTGRES_15"

  deletion_protection = false

  settings {
    tier = "db-f1-micro"

    backup_configuration {
      enabled = true
    }

    ip_configuration {
      ipv4_enabled = true
    }
  }
}

resource "google_sql_database" "app" {
  name     = "kube_train"
  instance = google_sql_database_instance.main.name
}

resource "google_sql_user" "app" {
  name     = "kube_train_user"
  instance = google_sql_database_instance.main.name
  password = var.db_password
}