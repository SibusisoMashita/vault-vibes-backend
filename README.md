# Vault Vibes Backend

Production deployment targets ECS Fargate with runtime configuration from AWS Secrets Manager.

## Required secrets

- `vaultvibes/prod/db` with keys: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
- `vaultvibes/prod/app` with keys: `S3_BUCKET` (optional: `APP_ENV`, `JWT_SECRET`)

## One-time runtime wiring

Run the helper script to verify secrets and apply least-privilege access on the backend runtime role.

```bash
./infrastructure/verify-and-wire-prod.sh
```

Optional overrides:

```bash
AWS_REGION=us-east-1 BACKEND_ROLE_NAME=vault-backend-role ./infrastructure/verify-and-wire-prod.sh
```

## Container secret injection (ECS)

The task definition template is `infrastructure/ecs-task-definition.prod.template.json`.
Secrets are injected into container env vars and consumed by Spring Boot (`application-prod.yml`).

## GitHub Actions deploy

Workflow: `.github/workflows/deploy-prod.yml`

Set these GitHub repository secrets:

- `AWS_GITHUB_DEPLOY_ROLE_ARN`
- `ECS_TASK_EXECUTION_ROLE_ARN`
- `ECS_TASK_ROLE_ARN`

Deploy command is executed in workflow via ECS update-service with force new deployment.

## Local verification

Run tests:

```bash
mvn -B test
```

