#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# PROD Terraform Import Script
# Run this ONCE to bring existing production resources under Terraform management.
# After import, run: terraform plan (should show zero changes if config matches)
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

cd "$(dirname "$0")"

echo "==> Initialising Terraform..."
terraform init

echo ""
echo "==> Importing PROD resources into Terraform state..."
echo "    This is read-only (import does NOT change AWS resources)."
echo ""

# ─── Security Groups ────────────────────────────────────────────────────────
terraform import aws_security_group.alb     sg-05e73c6c4716d29eb
terraform import aws_security_group.ecs     sg-093c2515a0031d64d

# ─── ACM Certificate ────────────────────────────────────────────────────────
terraform import aws_acm_certificate.prod \
  arn:aws:acm:us-east-1:861870144419:certificate/aefc95d7-997e-444e-be27-6ffc9bfaae36

# ─── ALB ────────────────────────────────────────────────────────────────────
terraform import aws_lb.prod \
  arn:aws:elasticloadbalancing:us-east-1:861870144419:loadbalancer/app/vault-vibes-backend-alb/60cebe78e152e645

terraform import aws_lb_target_group.prod \
  arn:aws:elasticloadbalancing:us-east-1:861870144419:targetgroup/vault-vibes-backend-tg/22c5e59339e1bd51

terraform import aws_lb_listener.http_redirect \
  arn:aws:elasticloadbalancing:us-east-1:861870144419:listener/app/vault-vibes-backend-alb/60cebe78e152e645/4dd6a392f163274d

terraform import aws_lb_listener.https \
  arn:aws:elasticloadbalancing:us-east-1:861870144419:listener/app/vault-vibes-backend-alb/60cebe78e152e645/2169e67d6e6aa3ea

# ─── ECR ────────────────────────────────────────────────────────────────────
terraform import aws_ecr_repository.api vaultvibes-api

# ─── CloudWatch ─────────────────────────────────────────────────────────────
terraform import aws_cloudwatch_log_group.prod_backend /ecs/vault-backend

# ─── Secrets Manager ────────────────────────────────────────────────────────
terraform import aws_secretsmanager_secret.prod_db \
  arn:aws:secretsmanager:us-east-1:861870144419:secret:vaultvibes/prod/db-$(
    aws secretsmanager describe-secret \
      --secret-id vaultvibes/prod/db \
      --query 'ARN' --output text | cut -d: -f7 | cut -d- -f1-7
  )
terraform import aws_secretsmanager_secret.prod_app \
  arn:aws:secretsmanager:us-east-1:861870144419:secret:vaultvibes/prod/app-$(
    aws secretsmanager describe-secret \
      --secret-id vaultvibes/prod/app \
      --query 'ARN' --output text | cut -d: -f7 | cut -d- -f1-7
  )

# ─── IAM Roles ──────────────────────────────────────────────────────────────
terraform import aws_iam_role.prod_ecs_exec     vaultvibes-prod-ecs-task-exec-role
terraform import aws_iam_role.prod_ecs_task     vaultvibes-prod-ecs-task-role
terraform import aws_iam_role.prod_github_deploy vaultvibes-prod-github-deploy-role

terraform import aws_iam_role_policy.prod_ecs_exec_secrets \
  vaultvibes-prod-ecs-task-exec-role:vaultvibes-prod-ecs-task-exec-inline

terraform import aws_iam_role_policy.prod_ecs_task_inline \
  vaultvibes-prod-ecs-task-role:vaultvibes-prod-ecs-task-inline

terraform import aws_iam_role_policy.prod_github_inline \
  vaultvibes-prod-github-deploy-role:vaultvibes-prod-github-inline

terraform import aws_iam_role_policy_attachment.prod_ecs_exec_managed \
  vaultvibes-prod-ecs-task-exec-role/arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy

# ─── S3 ─────────────────────────────────────────────────────────────────────
terraform import aws_s3_bucket.prod_frontend     vaultvibes-frontend
terraform import aws_s3_bucket_public_access_block.prod_frontend vaultvibes-frontend
terraform import aws_s3_bucket.prod_uploads      vault-vibes-uploads

# ─── CloudFront ─────────────────────────────────────────────────────────────
terraform import aws_cloudfront_distribution.prod_frontend E2LTR00BB2MIAY

# ─── Cognito ────────────────────────────────────────────────────────────────
terraform import aws_cognito_user_pool.prod         us-east-1_Pmg4WjBdm
terraform import aws_cognito_user_pool_client.prod_api \
  us-east-1_Pmg4WjBdm/3qsigtvtgu0jm2h0qdb2cthfh2
terraform import aws_cognito_user_pool_client.prod_spa \
  us-east-1_Pmg4WjBdm/79kvqh819qcj0kojp3560jvj1
terraform import aws_cognito_user_pool_domain.prod \
  vaultvibes-auth

# ─── Route53 ────────────────────────────────────────────────────────────────
terraform import aws_route53_record.prod_frontend \
  Z0794849BQM36WUODCU6_vaultvibes.co.za_A
terraform import aws_route53_record.prod_api \
  Z0794849BQM36WUODCU6_api.vaultvibes.co.za_A

echo ""
echo "==> Import complete."
echo ""
echo "NEXT STEP: Run 'terraform plan' and verify zero changes."
echo "If there are diffs, update main.tf to match the live config before proceeding."