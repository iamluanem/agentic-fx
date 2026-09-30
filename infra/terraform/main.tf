# Agentic FX on AWS.
#
#   Internet ─► ALB (HTTPS) ─► web (ECS Fargate) ─► fx-api, agent-service (private, service discovery)
#                                                  fx-api ─► RDS PostgreSQL · ElastiCache Redis · MSK Serverless (Kafka)
#
# Everything runs in private subnets except the ALB. Secrets live in Secrets Manager and are injected by ECS.
# The market simulator and /dev endpoints are disabled outside the dev profile.

locals {
  name = "agentic-fx-${var.environment}"
}

module "network" {
  source             = "./modules/network"
  name               = local.name
  cidr               = var.vpc_cidr
  availability_zones = var.availability_zones
}

module "data" {
  source              = "./modules/data"
  name                = local.name
  vpc_id              = module.network.vpc_id
  private_subnet_ids  = module.network.private_subnet_ids
  allowed_security_group_ids = [module.services.fx_api_security_group_id]
  db_instance_class   = var.db_instance_class
}

resource "random_password" "jwt" {
  length  = 64
  special = false
}

resource "aws_secretsmanager_secret" "app" {
  name                    = "${local.name}/app"
  recovery_window_in_days = 7
}

# ANTHROPIC_API_KEY and provider credentials are set out of band (console/CLI), never in Terraform state.
resource "aws_secretsmanager_secret_version" "app" {
  secret_id = aws_secretsmanager_secret.app.id
  secret_string = jsonencode({
    JWT_SECRET        = random_password.jwt.result
    DB_PASSWORD       = module.data.db_password
    ANTHROPIC_API_KEY = "" # set out of band; empty = deterministic fallback
  })
  lifecycle {
    ignore_changes = [secret_string]
  }
}

module "services" {
  source             = "./modules/services"
  name               = local.name
  region             = var.region
  vpc_id             = module.network.vpc_id
  public_subnet_ids  = module.network.public_subnet_ids
  private_subnet_ids = module.network.private_subnet_ids
  certificate_arn    = var.certificate_arn
  images             = var.images
  desired_count      = var.desired_count
  secret_arn         = aws_secretsmanager_secret.app.arn
  environment = {
    DB_URL                  = "jdbc:postgresql://${module.data.db_endpoint}/agenticfx"
    DB_USER                 = "agenticfx"
    REDIS_HOST              = module.data.redis_endpoint
    KAFKA_BOOTSTRAP_SERVERS = module.data.kafka_bootstrap_brokers
    EVENT_BROKER            = "kafka"
    FX_PROVIDER             = var.fx_provider
    SPRING_PROFILES_ACTIVE  = "otel"
    ANTHROPIC_MODEL         = var.anthropic_model
  }
}
