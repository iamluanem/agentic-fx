variable "name" { type = string }
variable "region" { type = string }
variable "vpc_id" { type = string }
variable "public_subnet_ids" { type = list(string) }
variable "private_subnet_ids" { type = list(string) }
variable "certificate_arn" { type = string }
variable "desired_count" { type = number }
variable "secret_arn" { type = string }
variable "environment" { type = map(string) }
variable "images" {
  type = object({ fx_api = string, agent_service = string, web = string })
}

locals {
  services = {
    fx-api = {
      image   = var.images.fx_api
      port    = 8080
      cpu     = 1024
      memory  = 2048
      health  = "/actuator/health/readiness"
      secrets = ["JWT_SECRET", "DB_PASSWORD"]
      env     = var.environment
    }
    agent-service = {
      image   = var.images.agent_service
      port    = 8000
      cpu     = 512
      memory  = 1024
      health  = "/health"
      secrets = ["JWT_SECRET", "ANTHROPIC_API_KEY"]
      env     = { FX_API_URL = "http://fx-api.${var.name}.local:8080", ANTHROPIC_MODEL = var.environment["ANTHROPIC_MODEL"] }
    }
    web = {
      image   = var.images.web
      port    = 3000
      cpu     = 512
      memory  = 1024
      health  = "/login"
      secrets = []
      env = {
        FX_API_URL    = "http://fx-api.${var.name}.local:8080"
        AGENT_API_URL = "http://agent-service.${var.name}.local:8000"
        COOKIE_SECURE = "true"
      }
    }
  }
}

resource "aws_ecs_cluster" "this" {
  name = var.name
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_service_discovery_private_dns_namespace" "this" {
  name = "${var.name}.local"
  vpc  = var.vpc_id
}

resource "aws_service_discovery_service" "this" {
  for_each = local.services
  name     = each.key
  dns_config {
    namespace_id = aws_service_discovery_private_dns_namespace.this.id
    dns_records {
      type = "A"
      ttl  = 10
    }
  }
}

# ------------------------------------------------------------------------------------------ security groups

resource "aws_security_group" "alb" {
  name_prefix = "${var.name}-alb-"
  vpc_id      = var.vpc_id
  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_security_group" "service" {
  for_each    = local.services
  name_prefix = "${var.name}-${each.key}-"
  vpc_id      = var.vpc_id
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# Only the ALB reaches web; only web and agent-service reach fx-api; only web reaches agent-service.
resource "aws_security_group_rule" "web_from_alb" {
  type                     = "ingress"
  security_group_id        = aws_security_group.service["web"].id
  source_security_group_id = aws_security_group.alb.id
  from_port                = 3000
  to_port                  = 3000
  protocol                 = "tcp"
}

resource "aws_security_group_rule" "fx_api_from_callers" {
  for_each                 = toset(["web", "agent-service"])
  type                     = "ingress"
  security_group_id        = aws_security_group.service["fx-api"].id
  source_security_group_id = aws_security_group.service[each.key].id
  from_port                = 8080
  to_port                  = 8080
  protocol                 = "tcp"
}

resource "aws_security_group_rule" "agent_from_web" {
  type                     = "ingress"
  security_group_id        = aws_security_group.service["agent-service"].id
  source_security_group_id = aws_security_group.service["web"].id
  from_port                = 8000
  to_port                  = 8000
  protocol                 = "tcp"
}

# ------------------------------------------------------------------------------------------ load balancer

resource "aws_lb" "this" {
  name               = var.name
  load_balancer_type = "application"
  subnets            = var.public_subnet_ids
  security_groups    = [aws_security_group.alb.id]
  drop_invalid_header_fields = true
}

resource "aws_lb_target_group" "web" {
  name        = "${var.name}-web"
  port        = 3000
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = var.vpc_id
  health_check {
    path = "/login"
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.web.arn
  }
}

# ------------------------------------------------------------------------------------------ tasks & services

resource "aws_cloudwatch_log_group" "this" {
  for_each          = local.services
  name              = "/ecs/${var.name}/${each.key}"
  retention_in_days = 30
}

data "aws_iam_policy_document" "assume_ecs" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name_prefix        = "${var.name}-exec-"
  assume_role_policy = data.aws_iam_policy_document.assume_ecs.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "read_secret" {
  role = aws_iam_role.execution.id
  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = [var.secret_arn] }]
  })
}

resource "aws_iam_role" "task" {
  name_prefix        = "${var.name}-task-"
  assume_role_policy = data.aws_iam_policy_document.assume_ecs.json
}

# fx-api publishes/consumes domain events on MSK Serverless with IAM auth.
resource "aws_iam_role_policy" "kafka" {
  role = aws_iam_role.task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["kafka-cluster:Connect", "kafka-cluster:DescribeTopic", "kafka-cluster:CreateTopic", "kafka-cluster:ReadData", "kafka-cluster:WriteData", "kafka-cluster:DescribeGroup", "kafka-cluster:AlterGroup"]
      Resource = ["*"]
    }]
  })
}

resource "aws_ecs_task_definition" "this" {
  for_each                 = local.services
  family                   = "${var.name}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = each.value.cpu
  memory                   = each.value.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }
  container_definitions = jsonencode([{
    name         = each.key
    image        = each.value.image
    essential    = true
    portMappings = [{ containerPort = each.value.port, protocol = "tcp" }]
    environment  = [for k, v in each.value.env : { name = k, value = v }]
    secrets      = [for s in each.value.secrets : { name = s, valueFrom = "${var.secret_arn}:${s}::" }]
    readonlyRootFilesystem = false
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.this[each.key].name
        awslogs-region        = var.region
        awslogs-stream-prefix = each.key
      }
    }
  }])
}

resource "aws_ecs_service" "this" {
  for_each        = local.services
  name            = each.key
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.this[each.key].arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets         = var.private_subnet_ids
    security_groups = [aws_security_group.service[each.key].id]
  }

  service_registries {
    registry_arn = aws_service_discovery_service.this[each.key].arn
  }

  dynamic "load_balancer" {
    for_each = each.key == "web" ? [1] : []
    content {
      target_group_arn = aws_lb_target_group.web.arn
      container_name   = "web"
      container_port   = 3000
    }
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
}

output "alb_dns_name" { value = aws_lb.this.dns_name }
output "fx_api_security_group_id" { value = aws_security_group.service["fx-api"].id }
