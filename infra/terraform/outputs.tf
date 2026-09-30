output "public_url" {
  description = "HTTPS endpoint of the web application."
  value       = "https://${module.services.alb_dns_name}"
}

output "app_secret_arn" {
  description = "Secrets Manager secret holding JWT_SECRET, DB_PASSWORD (and ANTHROPIC_API_KEY, set manually)."
  value       = aws_secretsmanager_secret.app.arn
}
