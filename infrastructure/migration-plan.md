# Vault Vibes — Multi-Env Migration Plan

## Current State
- Prod is live: ECS Fargate on `vault-vibes-cluster`, ALB, CloudFront, Cognito, RDS (shared postgres instance)
- No dev environment, no Terraform, CI/CD deploys prod only

## End State
| Layer | DEV | PROD |
|---|---|---|
| Frontend | `dev.vaultvibes.co.za` | `vaultvibes.co.za` |
| Backend | `dev-api.vaultvibes.co.za` | `api.vaultvibes.co.za` |
| Auth | Cognito pool `vaultvibes-dev-users` | Cognito pool `vaultvibes-prod-users` |
| Database | `vaultvibes_dev` on shared RDS | `vaultvibes` on shared RDS |
| CI/CD | PR/push → DEV (auto) | `main` → PROD (requires approval) |

---

## Phase 1 — Security Fixes (do first, no app impact)

**Time: ~30 min**

### 1a. Remove SSH ingress from prod ECS security group
The `vaultvibes-prod-api-sg` has port 22 open to `0.0.0.0/0`.
Fargate tasks have no SSH access by design — this rule is dead and a risk.

```bash
aws ec2 revoke-security-group-ingress \
  --group-id sg-093c2515a0031d64d \
  --protocol tcp --port 22 \
  --cidr 0.0.0.0/0 \
  --region us-east-1
```

### 1b. Review root account usage
Current AWS caller identity is the root account (`arn:aws:iam::861870144419:root`).
Root should not be used for day-to-day operations.

Action: Create an IAM admin user or use SSO, then restrict root.

### 1c. Tighten ECS task SG ingress
Ports 80 and 443 on the ECS task SG (`sg-093c2515a0031d64d`) are open to `0.0.0.0/0`.
These can be restricted to only the ALB SG since all traffic routes through the ALB.

This is tracked in the prod Terraform (main.tf has a comment); apply after import.

---

## Phase 2 — DEV Infrastructure

**Time: ~20 min + cert validation wait (~5 min)**

### Prerequisites
- AWS CLI authenticated
- Terraform >= 1.6 installed
- S3 state bucket `omnisolve-terraform-state` is accessible

### Steps

```bash
cd vault-vibes-backend/infrastructure/terraform/dev

# 1. Init
terraform init

# 2. Plan — review what will be created
terraform plan -out=dev.tfplan

# 3. Apply
terraform apply dev.tfplan

# 4. Save outputs — you'll need these for later steps
terraform output
```

Expected resources created (all new, nothing touched in prod):
- ACM certificate (`dev.vaultvibes.co.za`, `dev-api.vaultvibes.co.za`, `dev-auth.vaultvibes.co.za`)
- ALB listener cert attachment (adds dev cert to existing ALB listener — non-breaking)
- ALB listener rule priority 10: `dev-api.vaultvibes.co.za` → dev target group
- ECS security group `vaultvibes-dev-api-sg`
- Target group `vault-vibes-dev-backend-tg`
- CloudWatch log group `/ecs/vault-dev-backend`
- Secrets Manager: `vaultvibes/dev/backend/db` + `vaultvibes/dev/backend/app`
- IAM roles: `vaultvibes-dev-ecs-task-exec-role`, `vaultvibes-dev-ecs-task-role`, `vaultvibes-dev-github-deploy-role`, `vaultvibes-dev-frontend-github-deploy-role`
- S3 buckets: `vaultvibes-dev-frontend`, `vault-vibes-dev-uploads`
- CloudFront distribution for `dev.vaultvibes.co.za`
- Cognito user pool `vaultvibes-dev-users` + clients + prefix domain
- EventBridge bus `vault-vibes-dev-events`
- ECS task definition (bootstrap) + ECS service `vault-dev-backend-service`
- Route53 A records: `dev.vaultvibes.co.za`, `dev-api.vaultvibes.co.za`

---

## Phase 3 — Database Setup

**Time: ~10 min**

Connect to the RDS instance and run `infrastructure/db-setup.sql`:

```bash
# Option A: via psql with an SSH tunnel / bastion (if VPC-private)
psql "host=prod-omnisolve-postgres.cmrg4wcome3h.us-east-1.rds.amazonaws.com \
      port=5432 dbname=postgres user=postgres"

# Option B: via RDS Query Editor in AWS Console

# Paste contents of infrastructure/db-setup.sql
# Replace <secure_password> with a real password
```

Then update the dev secret:
```bash
aws secretsmanager put-secret-value \
  --secret-id vaultvibes/dev/backend/db \
  --secret-string '{
    "DB_URL":      "jdbc:postgresql://prod-omnisolve-postgres.cmrg4wcome3h.us-east-1.rds.amazonaws.com:5432/vaultvibes_dev",
    "DB_USERNAME": "vaultvibes_dev_app",
    "DB_PASSWORD": "<same_secure_password>"
  }'
```

---

## Phase 4 — Application Configuration

### Backend
The new `application-dev.yml` is already added. It uses env vars injected via ECS secrets:
- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` — from `vaultvibes/dev/backend/db`
- `S3_BUCKET`, `APP_ENV` — from `vaultvibes/dev/backend/app`
- `COGNITO_ISSUER_URI`, `COGNITO_USER_POOL_ID`, `COGNITO_CLIENT_ID` — from ECS task definition env vars

No change needed to `application.yml` (prod profile is unchanged).

### Frontend
DEV env vars are injected at build time by GitHub Actions via environment variables.
No `.env.dev` file is committed — values come from GitHub Environment `dev`.

---

## Phase 5 — CI/CD Setup

### 5a. Setup GitHub environments + variables

Fill in the Terraform output values at the top of `infrastructure/github-setup.sh`, then:

```bash
chmod +x infrastructure/github-setup.sh
./infrastructure/github-setup.sh
```

### 5b. Add prod approval reviewers (manual, in GitHub UI)
1. Go to: `https://github.com/SibusisoMashita/vault-vibes-backend/settings/environments`
2. Click `prod` → Add required reviewers (yourself or a team)
3. Repeat for `vault-vibes-frontend`

### 5c. Verify secrets exist for prod environments
The existing prod secrets (`AWS_GITHUB_DEPLOY_ROLE_ARN`, `ECS_TASK_EXECUTION_ROLE_ARN`, etc.)
must be moved or re-set under the `prod` environment scope, not repo-level.

```bash
# Check current repo-level secrets (these need to become env-scoped)
gh secret list --repo SibusisoMashita/vault-vibes-backend

# Re-set them under the prod environment
gh secret set AWS_GITHUB_DEPLOY_ROLE_ARN \
  --env prod \
  --repo SibusisoMashita/vault-vibes-backend \
  --body "arn:aws:iam::861870144419:role/vaultvibes-prod-github-deploy-role"
```

---

## Phase 6 — Prod Terraform Import (optional, run when ready)

This brings existing prod resources under Terraform management.
**This does NOT change any prod resources — import is read-only.**

```bash
cd vault-vibes-backend/infrastructure/terraform/prod

# Make import.sh executable
chmod +x import.sh

# Run imports
./import.sh

# Verify zero drift
terraform plan
# Expected: "No changes. Your infrastructure matches the configuration."
```

If `terraform plan` shows changes, update `prod/main.tf` to match the live state
before doing any `terraform apply`.

---

## Phase 7 — Validation Checklist

Run these checks after all phases complete:

```bash
# Backend health
curl -s https://dev-api.vaultvibes.co.za/actuator/health | python3 -m json.tool
curl -s https://api.vaultvibes.co.za/actuator/health | python3 -m json.tool

# Frontend loading
curl -sI https://dev.vaultvibes.co.za | grep -E "HTTP|content-type|cache-control"
curl -sI https://vaultvibes.co.za | grep -E "HTTP|content-type|cache-control"

# DB isolation check
# Connect to postgres as dev user and verify prod DB is inaccessible:
# psql "... user=vaultvibes_dev_app"
# \c vaultvibes  → should fail with "permission denied"

# CI/CD: open a PR to main → confirm DEV workflow triggers
# CI/CD: merge PR → confirm PROD workflow requires approval

# Cognito: confirm dev pool is separate from prod
aws cognito-idp list-user-pools --max-results 10 --region us-east-1
```

---

## GitHub Environment Variables Reference

### Backend — `dev` environment

| Name | Value | Type |
|---|---|---|
| `AWS_GITHUB_DEPLOY_ROLE_ARN` | `arn:aws:iam::861870144419:role/vaultvibes-dev-github-deploy-role` | Variable |
| `DEV_COGNITO_USER_POOL_ID` | from `terraform output dev_cognito_user_pool_id` | Variable |
| `DEV_COGNITO_API_CLIENT_ID` | from `terraform output dev_cognito_api_client_id` | Variable |

### Backend — `prod` environment

| Name | Value | Type |
|---|---|---|
| `AWS_GITHUB_DEPLOY_ROLE_ARN` | `arn:aws:iam::861870144419:role/vaultvibes-prod-github-deploy-role` | Secret |
| `ECS_TASK_EXECUTION_ROLE_ARN` | `arn:aws:iam::861870144419:role/vaultvibes-prod-ecs-task-exec-role` | Secret |
| `ECS_TASK_ROLE_ARN` | `arn:aws:iam::861870144419:role/vaultvibes-prod-ecs-task-role` | Secret |

### Frontend — `dev` environment

| Name | Value | Type |
|---|---|---|
| `AWS_ROLE_TO_ASSUME` | `arn:aws:iam::861870144419:role/vaultvibes-dev-frontend-github-deploy-role` | Variable |
| `S3_BUCKET` | `vaultvibes-dev-frontend` | Variable |
| `CLOUDFRONT_DISTRIBUTION_ID` | from `terraform output dev_cloudfront_distribution_id` | Variable |
| `VITE_API_BASE_URL` | `https://dev-api.vaultvibes.co.za/api` | Variable |
| `DEV_COGNITO_USER_POOL_ID` | from `terraform output dev_cognito_user_pool_id` | Variable |
| `DEV_COGNITO_SPA_CLIENT_ID` | from `terraform output dev_cognito_spa_client_id` | Variable |
| `DEV_COGNITO_DOMAIN` | `vaultvibes-dev-auth.auth.us-east-1.amazoncognito.com` | Variable |

### Frontend — `prod` environment

| Name | Value | Type |
|---|---|---|
| `AWS_ROLE_TO_ASSUME` | (existing secret) | Secret |
| `S3_BUCKET` | `vaultvibes-frontend` | Variable |
| `CLOUDFRONT_DISTRIBUTION_ID` | `E2LTR00BB2MIAY` | Variable |
| `VITE_API_BASE_URL` | `https://api.vaultvibes.co.za/api` | Variable |
