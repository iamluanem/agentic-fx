variable "name" { type = string }
variable "cidr" { type = string }
variable "availability_zones" { type = list(string) }

module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 5.13"

  name            = var.name
  cidr            = var.cidr
  azs             = var.availability_zones
  public_subnets  = [for i, _ in var.availability_zones : cidrsubnet(var.cidr, 8, i)]
  private_subnets = [for i, _ in var.availability_zones : cidrsubnet(var.cidr, 8, i + 10)]

  enable_nat_gateway   = true
  single_nat_gateway   = true
  enable_dns_hostnames = true
}

output "vpc_id" { value = module.vpc.vpc_id }
output "public_subnet_ids" { value = module.vpc.public_subnets }
output "private_subnet_ids" { value = module.vpc.private_subnets }
