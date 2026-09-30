environment        = "dev"
region             = "sa-east-1"
availability_zones = ["sa-east-1a", "sa-east-1b"]
desired_count      = 1
db_instance_class  = "db.t4g.small"
fx_provider        = "mock"
certificate_arn    = "arn:aws:acm:sa-east-1:000000000000:certificate/replace-me"
images = {
  fx_api        = "000000000000.dkr.ecr.sa-east-1.amazonaws.com/agentic-fx/fx-api:replace-me"
  agent_service = "000000000000.dkr.ecr.sa-east-1.amazonaws.com/agentic-fx/agent-service:replace-me"
  web           = "000000000000.dkr.ecr.sa-east-1.amazonaws.com/agentic-fx/web:replace-me"
}
