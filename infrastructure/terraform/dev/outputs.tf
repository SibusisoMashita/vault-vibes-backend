output "dev_cloudfront_distribution_id" {
  description = "CloudFront distribution ID for DEV frontend (needed for GitHub Actions invalidation)"
  value       = aws_cloudfront_distribution.dev_frontend.id
}

output "dev_cloudfront_domain" {
  description = "CloudFront domain for DEV frontend"
  value       = aws_cloudfront_distribution.dev_frontend.domain_name
}

output "dev_s3_frontend_bucket" {
  description = "S3 bucket name for DEV frontend assets"
  value       = aws_s3_bucket.dev_frontend.bucket
}

output "dev_cognito_user_pool_id" {
  description = "DEV Cognito User Pool ID"
  value       = aws_cognito_user_pool.dev.id
}

output "dev_cognito_api_client_id" {
  description = "DEV Cognito API client ID"
  value       = aws_cognito_user_pool_client.dev_api.id
}

output "dev_cognito_spa_client_id" {
  description = "DEV Cognito SPA public client ID"
  value       = aws_cognito_user_pool_client.dev_spa.id
}

output "dev_cognito_issuer_url" {
  description = "JWT issuer URL for Spring Security configuration"
  value       = "https://cognito-idp.us-east-1.amazonaws.com/${aws_cognito_user_pool.dev.id}"
}

output "dev_cognito_hosted_ui_url" {
  description = "Cognito hosted UI base URL"
  value       = "https://vaultvibes-dev-auth.auth.us-east-1.amazoncognito.com"
}

output "dev_github_backend_role_arn" {
  description = "IAM role ARN for GitHub Actions backend deploys"
  value       = aws_iam_role.dev_github_backend.arn
}

output "dev_github_frontend_role_arn" {
  description = "IAM role ARN for GitHub Actions frontend deploys"
  value       = aws_iam_role.dev_github_frontend.arn
}

output "dev_db_secret_arn" {
  description = "DEV DB secret ARN"
  value       = aws_secretsmanager_secret.dev_db.arn
}

output "dev_app_secret_arn" {
  description = "DEV app secret ARN"
  value       = aws_secretsmanager_secret.dev_app.arn
}

output "dev_acm_certificate_arn" {
  description = "ACM certificate ARN for dev subdomains"
  value       = aws_acm_certificate_validation.dev.certificate_arn
}

output "dev_ecs_service_name" {
  description = "ECS service name for GitHub Actions deploys"
  value       = aws_ecs_service.dev.name
}

output "dev_ecs_cluster_name" {
  description = "ECS cluster name"
  value       = "vault-vibes-cluster"
}