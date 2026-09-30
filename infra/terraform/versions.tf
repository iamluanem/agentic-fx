terraform {
  required_version = ">= 1.7"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.70"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
  # Remote state: configure per environment, e.g.
  # backend "s3" { bucket = "agentic-fx-tfstate" key = "dev/terraform.tfstate" region = "sa-east-1" dynamodb_table = "tf-locks" }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = {
      Project     = "agentic-fx"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
