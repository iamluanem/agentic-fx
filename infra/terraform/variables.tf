variable "environment" {
  description = "Environment name (dev, staging, prod)."
  type        = string
}

variable "region" {
  description = "AWS region."
  type        = string
  default     = "sa-east-1"
}

variable "vpc_cidr" {
  type    = string
  default = "10.40.0.0/16"
}

variable "availability_zones" {
  type    = list(string)
  default = ["sa-east-1a", "sa-east-1b"]
}

variable "images" {
  description = "Container images (ECR URIs with immutable tags) for each service."
  type = object({
    fx_api        = string
    agent_service = string
    web           = string
  })
}

variable "fx_provider" {
  description = "Liquidity provider used by fx-api: mock, wise or revolut."
  type        = string
  default     = "mock"
  validation {
    condition     = contains(["mock", "wise", "revolut"], var.fx_provider)
    error_message = "fx_provider must be mock, wise or revolut."
  }
}

variable "db_instance_class" {
  type    = string
  default = "db.t4g.medium"
}

variable "desired_count" {
  description = "Tasks per service."
  type        = number
  default     = 2
}

variable "certificate_arn" {
  description = "ACM certificate for the public HTTPS listener."
  type        = string
}

variable "anthropic_model" {
  type    = string
  default = "claude-sonnet-5-5"
}
