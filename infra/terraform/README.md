# Terraform (AWS) — structure for deployment

```
infra/terraform/
  main.tf, variables.tf, outputs.tf, versions.tf
  environments/dev.tfvars
  modules/network    VPC, public/private subnets, NAT
  modules/data       RDS PostgreSQL 16 (Multi-AZ, encrypted), ElastiCache Redis 7 (TLS), MSK Serverless (IAM)
  modules/services   ECS Fargate (ARM64) for fx-api, agent-service, web; ALB (TLS 1.3) → web only;
                     Cloud Map service discovery; per-service security groups; Secrets Manager injection
```

```bash
terraform init
terraform plan -var-file=environments/dev.tfvars
```

Network policy: only the ALB reaches `web`; only `web` and `agent-service` reach `fx-api`; only `web` reaches
`agent-service`; only `fx-api` reaches PostgreSQL, Redis and Kafka.

## Before the first apply

This is a deployment skeleton; it has been syntax-checked, not applied. Three application settings differ from
local Docker Compose and must be added when deploying:

1. **Kafka (MSK Serverless) uses IAM auth.** Add `software.amazon.msk:aws-msk-iam-auth` to fx-app and set
   `spring.kafka.properties.security.protocol=SASL_SSL`, `sasl.mechanism=AWS_MSK_IAM`,
   `sasl.jaas.config=software.amazon.msk.auth.iam.IAMLoginModule required;`,
   `sasl.client.callback.handler.class=software.amazon.msk.auth.iam.IAMClientCallbackHandler`.
2. **Redis in transit encryption** is on: set `SPRING_DATA_REDIS_SSL_ENABLED=true`.
3. **Secrets**: `ANTHROPIC_API_KEY` (and Wise/Revolut credentials if used) are written to the app secret out of
   band, never through Terraform variables, so they never land in state.

Also: publish images to ECR with immutable tags, configure the S3 backend in `versions.tf`, and point the
`otel` profile's `OTEL_EXPORTER_OTLP_ENDPOINT` at an ADOT collector sidecar or AWS X-Ray.
