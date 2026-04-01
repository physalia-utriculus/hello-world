# %% Configuration %%

locals {
  app_base_name = "hello-world"
  app_variant   = "base"
}

terraform {
  required_version = ">= 1.14"

  backend "gcs" {
    # bucket = <provided via command-line arguments>
    prefix = "apps/hello-world/base/support"
  }

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 7.25"
    }
  }
}

# %% Inputs %%

variable "shared_terraform_gcs_state_bucket_name" {
  description = "Shared Terraform GCS state bucket name."
  type        = string
}

variable "console_image_reference" {
  description = "Container image reference for the console service."
  type        = string
}

variable "worker_image_reference" {
  description = "Container image reference for the worker job."
  type        = string
}

# %% Remote app foundation state %%

data "terraform_remote_state" "app_foundation_state" {
  backend = "gcs"

  config = {
    bucket = var.shared_terraform_gcs_state_bucket_name
    prefix = "apps/${local.app_base_name}/${local.app_variant}/foundation"
  }
}

locals {
  gcp_primary_location          = data.terraform_remote_state.app_foundation_state.outputs.gcp_primary_location
  gcp_app_project_id            = data.terraform_remote_state.app_foundation_state.outputs.gcp_app_project_id
  gcp_app_service_account_email = data.terraform_remote_state.app_foundation_state.outputs.gcp_app_service_account_email
}

# %% App GCP project setup %%

provider "google" {
  project = local.gcp_app_project_id
  region  = local.gcp_primary_location
}

# Firestore database
resource "google_firestore_database" "main_firestore_database" {
  name        = "(default)"
  location_id = local.gcp_primary_location
  type        = "FIRESTORE_NATIVE"
}

# Cloud Run service
resource "google_cloud_run_v2_service" "app_service" {
  name                = "hello-world-console"
  location            = local.gcp_primary_location
  deletion_protection = false # This project is experimental

  template {
    service_account = local.gcp_app_service_account_email

    containers {
      image = var.console_image_reference

      ports {
        container_port = 8080
      }

      env {
        name  = "GCP_PROJECT_ID"
        value = local.gcp_app_project_id
      }

      env {
        name  = "GCP_REGION"
        value = local.gcp_primary_location
      }

      env {
        name  = "WORKER_JOB_NAME"
        value = google_cloud_run_v2_job.session_worker.name
      }

      resources {
        limits = {
          cpu    = "1"
          memory = "512Mi"
        }
      }
    }

    scaling {
      min_instance_count = 0
      max_instance_count = 10
    }
  }

  traffic {
    type    = "TRAFFIC_TARGET_ALLOCATION_TYPE_LATEST"
    percent = 100
  }
}

resource "google_cloud_run_v2_job" "session_worker" {
  name                = "hello-world-worker"
  location            = local.gcp_primary_location
  deletion_protection = false # This project is experimental

  template {
    task_count  = 1
    parallelism = 1

    template {
      service_account = local.gcp_app_service_account_email
      max_retries     = 0
      timeout         = "7200s"

      containers {
        image = var.worker_image_reference

        env {
          name  = "GCP_PROJECT_ID"
          value = local.gcp_app_project_id
        }

        env {
          name  = "GCP_REGION"
          value = local.gcp_primary_location
        }

        resources {
          limits = {
            cpu    = "1"
            memory = "512Mi"
          }
        }
      }
    }
  }
}

# Allow unauthenticated access to Cloud Run
resource "google_cloud_run_v2_service_iam_member" "public" {
  project  = local.gcp_app_project_id
  location = local.gcp_primary_location
  name     = google_cloud_run_v2_service.app_service.name
  role     = "roles/run.invoker"
  member   = "allUsers"
}

# %% Outputs %%

output "service_url" {
  value = google_cloud_run_v2_service.app_service.uri
}

output "worker_job_name" {
  value = google_cloud_run_v2_job.session_worker.name
}
