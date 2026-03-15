#!/usr/bin/env bash
set -euo pipefail

# ==============================
# Vault Vibes Production Infra
# ==============================

REGION=us-east-1
PROJECT=vaultvibes
ENV=prod
ECR_REPO=vaultvibes-api
S3_BUCKET=vault-vibes-uploads
LOG_GROUP=/vaultvibes/prod/api

# GitHub OIDC trust scope (required for deploy role trust policy)
GITHUB_REPO=${GITHUB_REPO:-REPLACE_ORG/REPLACE_REPO}

# Deterministic resource names
COGNITO_POOL_NAME="${PROJECT}-${ENV}-users"
COGNITO_CLIENT_NAME="${PROJECT}-${ENV}-api-client"
COGNITO_SMS_ROLE_NAME="${PROJECT}-${ENV}-cognito-sms-role"
COGNITO_SMS_EXTERNAL_ID=""
EC2_ROLE_NAME="${PROJECT}-${ENV}-ec2-role"
EC2_INSTANCE_PROFILE_NAME="${PROJECT}-${ENV}-ec2-instance-profile"
GITHUB_ROLE_NAME="${PROJECT}-${ENV}-github-deploy-role"
SECURITY_GROUP_NAME="${PROJECT}-${ENV}-api-sg"
INSTANCE_NAME="${PROJECT}-${ENV}-api"
DB_SECRET_NAME="${PROJECT}/${ENV}/db"
APP_SECRET_NAME="${PROJECT}/${ENV}/app"

TAG_PROJECT_KEY="Project"
TAG_PROJECT_VALUE="${PROJECT}"
TAG_ENV_KEY="Environment"
TAG_ENV_VALUE="${ENV}"

AWS_PAGER=""
export AWS_PAGER

err() {
  echo "ERROR: $*" >&2
}

note() {
  echo "[INFO] $*"
}

aws_cli() {
  local output
  if ! output=$(aws --region "$REGION" "$@" 2>&1); then
    err "aws $*"
    err "$output"
    exit 1
  fi
  printf '%s' "$output"
}

aws_cli_optional() {
  # Non-fatal AWS call used for existence checks.
  aws --region "$REGION" "$@" 2>/dev/null
}

require_command() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    err "Required command '$cmd' is not installed."
    exit 1
  fi
}

apply_standard_tags_to_ec2_resource() {
  local resource_id="$1"
  aws_cli ec2 create-tags \
    --resources "$resource_id" \
    --tags "Key=${TAG_PROJECT_KEY},Value=${TAG_PROJECT_VALUE}" "Key=${TAG_ENV_KEY},Value=${TAG_ENV_VALUE}" >/dev/null
}

json_escape() {
  # Escape JSON string values for inline policy/trust docs.
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

ensure_prerequisites() {
  note "Validating prerequisites..."
  require_command aws

  if ! aws_cli_optional sts get-caller-identity >/dev/null; then
    err "AWS authentication failed. Run 'aws configure' or your SSO login command first."
    exit 1
  fi

  ACCOUNT_ID=$(aws_cli sts get-caller-identity --query Account --output text)
  if [[ -z "$ACCOUNT_ID" || "$ACCOUNT_ID" == "None" ]]; then
    err "Unable to resolve AWS account ID."
    exit 1
  fi

  note "Authenticated in account: ${ACCOUNT_ID}, region: ${REGION}"
}

ensure_cognito_user_pool_and_client() {
  note "Ensuring Cognito User Pool and App Client..."

  ensure_cognito_sms_role

  COGNITO_USER_POOL_ID=$(aws_cli cognito-idp list-user-pools \
    --max-results 60 \
    --query "UserPools[?Name=='${COGNITO_POOL_NAME}'].Id | [0]" \
    --output text)

  if [[ -z "$COGNITO_USER_POOL_ID" || "$COGNITO_USER_POOL_ID" == "None" ]]; then
    COGNITO_USER_POOL_ID=$(aws_cli cognito-idp create-user-pool \
      --pool-name "$COGNITO_POOL_NAME" \
      --alias-attributes phone_number \
      --auto-verified-attributes phone_number \
      --verification-message-template '{"SmsMessage":"Your Vault Vibes verification code is {####}"}' \
      --sms-configuration "SnsCallerArn=${COGNITO_SMS_ROLE_ARN},ExternalId=${COGNITO_SMS_EXTERNAL_ID}" \
      --user-pool-tags "${TAG_PROJECT_KEY}=${TAG_PROJECT_VALUE},${TAG_ENV_KEY}=${TAG_ENV_VALUE}" \
      --query 'UserPool.Id' \
      --output text)
    note "Created Cognito User Pool: ${COGNITO_USER_POOL_ID}"
  else
    note "Reusing Cognito User Pool: ${COGNITO_USER_POOL_ID}"
  fi

  COGNITO_APP_CLIENT_ID=$(aws_cli cognito-idp list-user-pool-clients \
    --user-pool-id "$COGNITO_USER_POOL_ID" \
    --max-results 60 \
    --query "UserPoolClients[?ClientName=='${COGNITO_CLIENT_NAME}'].ClientId | [0]" \
    --output text)

  if [[ -z "$COGNITO_APP_CLIENT_ID" || "$COGNITO_APP_CLIENT_ID" == "None" ]]; then
    COGNITO_APP_CLIENT_ID=$(aws_cli cognito-idp create-user-pool-client \
      --user-pool-id "$COGNITO_USER_POOL_ID" \
      --client-name "$COGNITO_CLIENT_NAME" \
      --generate-secret \
      --query 'UserPoolClient.ClientId' \
      --output text)
    note "Created Cognito App Client: ${COGNITO_APP_CLIENT_ID}"
  else
    note "Reusing Cognito App Client: ${COGNITO_APP_CLIENT_ID}"
  fi

  COGNITO_ISSUER_URL="https://cognito-idp.${REGION}.amazonaws.com/${COGNITO_USER_POOL_ID}"
}

ensure_cognito_sms_role() {
  note "Ensuring Cognito SMS IAM role..."

  COGNITO_SMS_EXTERNAL_ID="${PROJECT}-${ENV}-${ACCOUNT_ID}-cognito-sms"

  local sms_trust_policy
  sms_trust_policy=$(cat <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "cognito-idp.amazonaws.com"
      },
      "Action": "sts:AssumeRole",
      "Condition": {
        "StringEquals": {
          "sts:ExternalId": "${COGNITO_SMS_EXTERNAL_ID}"
        },
        "ArnLike": {
          "aws:SourceArn": "arn:aws:cognito-idp:${REGION}:${ACCOUNT_ID}:userpool/*"
        }
      }
    }
  ]
}
JSON
)

  COGNITO_SMS_ROLE_ARN=$(aws_cli_optional iam get-role --role-name "$COGNITO_SMS_ROLE_NAME" --query 'Role.Arn' --output text || true)
  if [[ -z "$COGNITO_SMS_ROLE_ARN" || "$COGNITO_SMS_ROLE_ARN" == "None" ]]; then
    COGNITO_SMS_ROLE_ARN=$(aws_cli iam create-role \
      --role-name "$COGNITO_SMS_ROLE_NAME" \
      --assume-role-policy-document "$sms_trust_policy" \
      --tags Key="$TAG_PROJECT_KEY",Value="$TAG_PROJECT_VALUE" Key="$TAG_ENV_KEY",Value="$TAG_ENV_VALUE" \
      --query 'Role.Arn' \
      --output text)
    note "Created Cognito SMS role: ${COGNITO_SMS_ROLE_NAME}"
  else
    note "Reusing Cognito SMS role: ${COGNITO_SMS_ROLE_NAME}"
    aws_cli iam update-assume-role-policy \
      --role-name "$COGNITO_SMS_ROLE_NAME" \
      --policy-document "$sms_trust_policy" >/dev/null
  fi

  local sms_publish_policy
  sms_publish_policy=$(cat <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "sns:Publish",
      "Resource": "*"
    }
  ]
}
JSON
)

  aws_cli iam put-role-policy \
    --role-name "$COGNITO_SMS_ROLE_NAME" \
    --policy-name "${PROJECT}-${ENV}-cognito-sms-inline" \
    --policy-document "$sms_publish_policy" >/dev/null
}

ensure_ecr_repository() {
  note "Ensuring ECR repository..."

  ECR_REPOSITORY_URI=$(aws_cli_optional ecr describe-repositories \
    --repository-names "$ECR_REPO" \
    --query 'repositories[0].repositoryUri' \
    --output text || true)

  if [[ -z "$ECR_REPOSITORY_URI" || "$ECR_REPOSITORY_URI" == "None" ]]; then
    ECR_REPOSITORY_URI=$(aws_cli ecr create-repository \
      --repository-name "$ECR_REPO" \
      --image-tag-mutability IMMUTABLE \
      --image-scanning-configuration scanOnPush=true \
      --tags Key="$TAG_PROJECT_KEY",Value="$TAG_PROJECT_VALUE" Key="$TAG_ENV_KEY",Value="$TAG_ENV_VALUE" \
      --query 'repository.repositoryUri' \
      --output text)
    note "Created ECR repository: ${ECR_REPOSITORY_URI}"
  else
    note "Reusing ECR repository: ${ECR_REPOSITORY_URI}"
    aws_cli ecr put-image-tag-mutability --repository-name "$ECR_REPO" --image-tag-mutability IMMUTABLE >/dev/null
    aws_cli ecr put-image-scanning-configuration --repository-name "$ECR_REPO" --image-scanning-configuration scanOnPush=true >/dev/null
  fi
}

ensure_github_oidc_provider() {
  note "Ensuring GitHub OIDC provider..."

  GITHUB_OIDC_URL="https://token.actions.githubusercontent.com"
  GITHUB_OIDC_HOST="token.actions.githubusercontent.com"
  GITHUB_OIDC_ARN=$(aws_cli iam list-open-id-connect-providers \
    --query "OpenIDConnectProviderList[?contains(Arn, '${GITHUB_OIDC_HOST}')].Arn | [0]" \
    --output text)

  if [[ -z "$GITHUB_OIDC_ARN" || "$GITHUB_OIDC_ARN" == "None" ]]; then
    GITHUB_OIDC_ARN=$(aws_cli iam create-open-id-connect-provider \
      --url "$GITHUB_OIDC_URL" \
      --client-id-list sts.amazonaws.com \
      --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1 \
      --tags Key="$TAG_PROJECT_KEY",Value="$TAG_PROJECT_VALUE" Key="$TAG_ENV_KEY",Value="$TAG_ENV_VALUE" \
      --query 'OpenIDConnectProviderArn' \
      --output text)
    note "Created GitHub OIDC provider: ${GITHUB_OIDC_ARN}"
  else
    note "Reusing GitHub OIDC provider: ${GITHUB_OIDC_ARN}"
  fi
}

ensure_iam_roles() {
  note "Ensuring IAM roles and instance profile..."

  local ec2_trust_policy
  ec2_trust_policy=$(cat <<'JSON'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "ec2.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
JSON
)

  EC2_ROLE_ARN=$(aws_cli_optional iam get-role --role-name "$EC2_ROLE_NAME" --query 'Role.Arn' --output text || true)
  if [[ -z "$EC2_ROLE_ARN" || "$EC2_ROLE_ARN" == "None" ]]; then
    EC2_ROLE_ARN=$(aws_cli iam create-role \
      --role-name "$EC2_ROLE_NAME" \
      --assume-role-policy-document "$ec2_trust_policy" \
      --tags Key="$TAG_PROJECT_KEY",Value="$TAG_PROJECT_VALUE" Key="$TAG_ENV_KEY",Value="$TAG_ENV_VALUE" \
      --query 'Role.Arn' \
      --output text)
    note "Created EC2 role: ${EC2_ROLE_NAME}"
  else
    note "Reusing EC2 role: ${EC2_ROLE_NAME}"
  fi

  local ec2_policy
  ec2_policy=$(cat <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "EcrPull",
      "Effect": "Allow",
      "Action": [
        "ecr:GetAuthorizationToken",
        "ecr:BatchGetImage",
        "ecr:GetDownloadUrlForLayer",
        "ecr:BatchCheckLayerAvailability"
      ],
      "Resource": "*"
    },
    {
      "Sid": "ReadSecrets",
      "Effect": "Allow",
      "Action": [
        "secretsmanager:GetSecretValue",
        "secretsmanager:DescribeSecret"
      ],
      "Resource": "arn:aws:secretsmanager:${REGION}:${ACCOUNT_ID}:secret:${PROJECT}/${ENV}/*"
    },
    {
      "Sid": "CloudWatchLogsWrite",
      "Effect": "Allow",
      "Action": [
        "logs:CreateLogStream",
        "logs:PutLogEvents",
        "logs:DescribeLogStreams"
      ],
      "Resource": [
        "arn:aws:logs:${REGION}:${ACCOUNT_ID}:log-group:${LOG_GROUP}",
        "arn:aws:logs:${REGION}:${ACCOUNT_ID}:log-group:${LOG_GROUP}:*"
      ]
    },
    {
      "Sid": "S3Exports",
      "Effect": "Allow",
      "Action": [
        "s3:ListBucket",
        "s3:GetObject",
        "s3:PutObject"
      ],
      "Resource": [
        "arn:aws:s3:::${S3_BUCKET}",
        "arn:aws:s3:::${S3_BUCKET}/*"
      ]
    }
  ]
}
JSON
)

  aws_cli iam put-role-policy \
    --role-name "$EC2_ROLE_NAME" \
    --policy-name "${PROJECT}-${ENV}-ec2-inline" \
    --policy-document "$ec2_policy" >/dev/null

  EC2_INSTANCE_PROFILE_ARN=$(aws_cli_optional iam get-instance-profile \
    --instance-profile-name "$EC2_INSTANCE_PROFILE_NAME" \
    --query 'InstanceProfile.Arn' \
    --output text || true)

  if [[ -z "$EC2_INSTANCE_PROFILE_ARN" || "$EC2_INSTANCE_PROFILE_ARN" == "None" ]]; then
    EC2_INSTANCE_PROFILE_ARN=$(aws_cli iam create-instance-profile \
      --instance-profile-name "$EC2_INSTANCE_PROFILE_NAME" \
      --tags Key="$TAG_PROJECT_KEY",Value="$TAG_PROJECT_VALUE" Key="$TAG_ENV_KEY",Value="$TAG_ENV_VALUE" \
      --query 'InstanceProfile.Arn' \
      --output text)
    note "Created EC2 instance profile: ${EC2_INSTANCE_PROFILE_NAME}"
  else
    note "Reusing EC2 instance profile: ${EC2_INSTANCE_PROFILE_NAME}"
  fi

  local profile_role
  profile_role=$(aws_cli iam get-instance-profile \
    --instance-profile-name "$EC2_INSTANCE_PROFILE_NAME" \
    --query "InstanceProfile.Roles[?RoleName=='${EC2_ROLE_NAME}'].RoleName | [0]" \
    --output text)

  if [[ -z "$profile_role" || "$profile_role" == "None" ]]; then
    aws_cli iam add-role-to-instance-profile \
      --instance-profile-name "$EC2_INSTANCE_PROFILE_NAME" \
      --role-name "$EC2_ROLE_NAME" >/dev/null
    note "Attached role ${EC2_ROLE_NAME} to instance profile ${EC2_INSTANCE_PROFILE_NAME}"
  fi

  ensure_github_oidc_provider

  local github_role_assume_policy
  github_role_assume_policy=$(cat <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "${GITHUB_OIDC_ARN}"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
        },
        "StringLike": {
          "token.actions.githubusercontent.com:sub": "repo:${GITHUB_REPO}:*"
        }
      }
    }
  ]
}
JSON
)

  GITHUB_ROLE_ARN=$(aws_cli_optional iam get-role --role-name "$GITHUB_ROLE_NAME" --query 'Role.Arn' --output text || true)
  if [[ -z "$GITHUB_ROLE_ARN" || "$GITHUB_ROLE_ARN" == "None" ]]; then
    GITHUB_ROLE_ARN=$(aws_cli iam create-role \
      --role-name "$GITHUB_ROLE_NAME" \
      --assume-role-policy-document "$github_role_assume_policy" \
      --tags Key="$TAG_PROJECT_KEY",Value="$TAG_PROJECT_VALUE" Key="$TAG_ENV_KEY",Value="$TAG_ENV_VALUE" \
      --query 'Role.Arn' \
      --output text)
    note "Created GitHub deployment role: ${GITHUB_ROLE_NAME}"
  else
    note "Reusing GitHub deployment role: ${GITHUB_ROLE_NAME}"
    aws_cli iam update-assume-role-policy \
      --role-name "$GITHUB_ROLE_NAME" \
      --policy-document "$github_role_assume_policy" >/dev/null
  fi

  local github_policy
  github_policy=$(cat <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "EcrPushPull",
      "Effect": "Allow",
      "Action": [
        "ecr:GetAuthorizationToken",
        "ecr:BatchGetImage",
        "ecr:GetDownloadUrlForLayer",
        "ecr:BatchCheckLayerAvailability",
        "ecr:InitiateLayerUpload",
        "ecr:UploadLayerPart",
        "ecr:CompleteLayerUpload",
        "ecr:PutImage"
      ],
      "Resource": [
        "arn:aws:ecr:${REGION}:${ACCOUNT_ID}:repository/${ECR_REPO}",
        "*"
      ]
    },
    {
      "Sid": "SecretsRead",
      "Effect": "Allow",
      "Action": [
        "secretsmanager:GetSecretValue",
        "secretsmanager:DescribeSecret"
      ],
      "Resource": "arn:aws:secretsmanager:${REGION}:${ACCOUNT_ID}:secret:${PROJECT}/${ENV}/*"
    },
    {
      "Sid": "CloudWatchLogsWrite",
      "Effect": "Allow",
      "Action": [
        "logs:CreateLogStream",
        "logs:PutLogEvents",
        "logs:DescribeLogStreams"
      ],
      "Resource": [
        "arn:aws:logs:${REGION}:${ACCOUNT_ID}:log-group:${LOG_GROUP}",
        "arn:aws:logs:${REGION}:${ACCOUNT_ID}:log-group:${LOG_GROUP}:*"
      ]
    },
    {
      "Sid": "S3Exports",
      "Effect": "Allow",
      "Action": [
        "s3:ListBucket",
        "s3:GetObject",
        "s3:PutObject"
      ],
      "Resource": [
        "arn:aws:s3:::${S3_BUCKET}",
        "arn:aws:s3:::${S3_BUCKET}/*"
      ]
    }
  ]
}
JSON
)

  aws_cli iam put-role-policy \
    --role-name "$GITHUB_ROLE_NAME" \
    --policy-name "${PROJECT}-${ENV}-github-inline" \
    --policy-document "$github_policy" >/dev/null
}

ensure_s3_bucket() {
  note "Ensuring S3 bucket..."

  if aws s3api head-bucket --bucket "$S3_BUCKET" >/dev/null 2>&1; then
    note "Reusing S3 bucket: ${S3_BUCKET}"
  else
    if [[ "$REGION" == "us-east-1" ]]; then
      aws_cli s3api create-bucket --bucket "$S3_BUCKET" >/dev/null
    else
      aws_cli s3api create-bucket \
        --bucket "$S3_BUCKET" \
        --create-bucket-configuration "LocationConstraint=${REGION}" >/dev/null
    fi
    note "Created S3 bucket: ${S3_BUCKET}"
  fi

  aws_cli s3api put-bucket-encryption \
    --bucket "$S3_BUCKET" \
    --server-side-encryption-configuration '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}' >/dev/null

  aws_cli s3api put-bucket-versioning \
    --bucket "$S3_BUCKET" \
    --versioning-configuration Status=Enabled >/dev/null

  aws_cli s3api put-public-access-block \
    --bucket "$S3_BUCKET" \
    --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true >/dev/null

  aws_cli s3api put-bucket-tagging \
    --bucket "$S3_BUCKET" \
    --tagging "TagSet=[{Key=${TAG_PROJECT_KEY},Value=${TAG_PROJECT_VALUE}},{Key=${TAG_ENV_KEY},Value=${TAG_ENV_VALUE}}]" >/dev/null
}

ensure_cloudwatch_log_group() {
  note "Ensuring CloudWatch log group..."

  local existing_log_group
  existing_log_group=$(aws_cli logs describe-log-groups \
    --log-group-name-prefix "$LOG_GROUP" \
    --query "logGroups[?logGroupName=='${LOG_GROUP}'].logGroupName | [0]" \
    --output text)

  if [[ -z "$existing_log_group" || "$existing_log_group" == "None" ]]; then
    aws_cli logs create-log-group \
      --log-group-name "$LOG_GROUP" \
      --tags "${TAG_PROJECT_KEY}=${TAG_PROJECT_VALUE},${TAG_ENV_KEY}=${TAG_ENV_VALUE}" >/dev/null
    note "Created log group: ${LOG_GROUP}"
  else
    note "Reusing log group: ${LOG_GROUP}"
  fi

  aws_cli logs put-retention-policy \
    --log-group-name "$LOG_GROUP" \
    --retention-in-days 30 >/dev/null
}

ensure_secrets_manager_secrets() {
  note "Ensuring Secrets Manager entries..."

  local db_secret_payload
  db_secret_payload='{"DB_URL":"jdbc:postgresql://prod-omnisolve-postgres.cmrg4wcome3h.us-east-1.rds.amazonaws.com:5432/vaultvibes","DB_USERNAME":"vaultvibes_app","DB_PASSWORD":"REPLACE"}'

  local app_secret_payload
  app_secret_payload='{"APP_ENV":"prod","JWT_SECRET":"REPLACE","S3_BUCKET":"vault-vibes-uploads"}'

  DB_SECRET_ARN=$(aws_cli_optional secretsmanager describe-secret --secret-id "$DB_SECRET_NAME" --query 'ARN' --output text || true)
  if [[ -z "$DB_SECRET_ARN" || "$DB_SECRET_ARN" == "None" ]]; then
    DB_SECRET_ARN=$(aws_cli secretsmanager create-secret \
      --name "$DB_SECRET_NAME" \
      --description "Vault Vibes production database credentials" \
      --secret-string "$db_secret_payload" \
      --tags Key="$TAG_PROJECT_KEY",Value="$TAG_PROJECT_VALUE" Key="$TAG_ENV_KEY",Value="$TAG_ENV_VALUE" \
      --query 'ARN' \
      --output text)
    note "Created secret: ${DB_SECRET_NAME}"
  else
    note "Reusing secret: ${DB_SECRET_NAME}"
  fi

  APP_SECRET_ARN=$(aws_cli_optional secretsmanager describe-secret --secret-id "$APP_SECRET_NAME" --query 'ARN' --output text || true)
  if [[ -z "$APP_SECRET_ARN" || "$APP_SECRET_ARN" == "None" ]]; then
    APP_SECRET_ARN=$(aws_cli secretsmanager create-secret \
      --name "$APP_SECRET_NAME" \
      --description "Vault Vibes production app configuration" \
      --secret-string "$app_secret_payload" \
      --tags Key="$TAG_PROJECT_KEY",Value="$TAG_PROJECT_VALUE" Key="$TAG_ENV_KEY",Value="$TAG_ENV_VALUE" \
      --query 'ARN' \
      --output text)
    note "Created secret: ${APP_SECRET_NAME}"
  else
    note "Reusing secret: ${APP_SECRET_NAME}"
  fi
}

ensure_ec2_infrastructure() {
  note "Ensuring EC2 networking and instance..."

  DEFAULT_VPC_ID=$(aws_cli ec2 describe-vpcs \
    --filters Name=isDefault,Values=true \
    --query 'Vpcs[0].VpcId' \
    --output text)

  if [[ -z "$DEFAULT_VPC_ID" || "$DEFAULT_VPC_ID" == "None" ]]; then
    err "No default VPC found in region ${REGION}."
    exit 1
  fi

  SECURITY_GROUP_ID=$(aws_cli_optional ec2 describe-security-groups \
    --filters Name=group-name,Values="$SECURITY_GROUP_NAME" Name=vpc-id,Values="$DEFAULT_VPC_ID" \
    --query 'SecurityGroups[0].GroupId' \
    --output text || true)

  if [[ -z "$SECURITY_GROUP_ID" || "$SECURITY_GROUP_ID" == "None" ]]; then
    SECURITY_GROUP_ID=$(aws_cli ec2 create-security-group \
      --group-name "$SECURITY_GROUP_NAME" \
      --description "${PROJECT} ${ENV} API security group" \
      --vpc-id "$DEFAULT_VPC_ID" \
      --query 'GroupId' \
      --output text)
    note "Created security group: ${SECURITY_GROUP_ID}"
  else
    note "Reusing security group: ${SECURITY_GROUP_ID}"
  fi

  apply_standard_tags_to_ec2_resource "$SECURITY_GROUP_ID"

  add_ingress_if_missing() {
    local port="$1"
    local rule_exists
    rule_exists=$(aws_cli ec2 describe-security-groups \
      --group-ids "$SECURITY_GROUP_ID" \
      --query "SecurityGroups[0].IpPermissions[?FromPort==\`${port}\` && ToPort==\`${port}\` && IpProtocol=='tcp' && IpRanges[?CidrIp=='0.0.0.0/0']].FromPort | [0]" \
      --output text)

    if [[ -z "$rule_exists" || "$rule_exists" == "None" ]]; then
      aws_cli ec2 authorize-security-group-ingress \
        --group-id "$SECURITY_GROUP_ID" \
        --protocol tcp \
        --port "$port" \
        --cidr 0.0.0.0/0 >/dev/null
      note "Added ingress rule tcp/${port} 0.0.0.0/0"
    fi
  }

  add_ingress_if_missing 80
  add_ingress_if_missing 443
  add_ingress_if_missing 22

  EXISTING_INSTANCE_ID=$(aws_cli ec2 describe-instances \
    --filters \
      Name=tag:Name,Values="$INSTANCE_NAME" \
      Name=instance-state-name,Values=pending,running,stopping,stopped \
    --query 'Reservations[0].Instances[0].InstanceId' \
    --output text)

  if [[ -n "$EXISTING_INSTANCE_ID" && "$EXISTING_INSTANCE_ID" != "None" ]]; then
    EC2_INSTANCE_ID="$EXISTING_INSTANCE_ID"
    note "Reusing EC2 instance: ${EC2_INSTANCE_ID}"
  else
    AL2023_AMI_ID=$(aws_cli ssm get-parameter \
      --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
      --query 'Parameter.Value' \
      --output text)

    USER_DATA=$(cat <<'EOF'
#!/bin/bash
set -euxo pipefail
dnf update -y
dnf install -y docker
systemctl enable --now docker
usermod -aG docker ec2-user
EOF
)

    EC2_INSTANCE_ID=$(aws_cli ec2 run-instances \
      --image-id "$AL2023_AMI_ID" \
      --instance-type t3.small \
      --iam-instance-profile Name="$EC2_INSTANCE_PROFILE_NAME" \
      --security-group-ids "$SECURITY_GROUP_ID" \
      --user-data "$USER_DATA" \
      --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=${INSTANCE_NAME}},{Key=${TAG_PROJECT_KEY},Value=${TAG_PROJECT_VALUE}},{Key=${TAG_ENV_KEY},Value=${TAG_ENV_VALUE}}]" \
      --query 'Instances[0].InstanceId' \
      --output text)

    note "Launched EC2 instance: ${EC2_INSTANCE_ID}"
  fi

  EC2_INSTANCE_PUBLIC_IP=$(aws_cli ec2 describe-instances \
    --instance-ids "$EC2_INSTANCE_ID" \
    --query 'Reservations[0].Instances[0].PublicIpAddress' \
    --output text)

  if [[ "$EC2_INSTANCE_PUBLIC_IP" == "None" ]]; then
    EC2_INSTANCE_PUBLIC_IP="pending"
  fi
}

print_outputs() {
  cat <<EOF

========================================
Vault Vibes Production Infrastructure
========================================
Cognito User Pool ID : ${COGNITO_USER_POOL_ID}
Cognito App Client ID: ${COGNITO_APP_CLIENT_ID}
Cognito Issuer URL   : ${COGNITO_ISSUER_URL}

EC2 Instance ID      : ${EC2_INSTANCE_ID}
EC2 Public IP        : ${EC2_INSTANCE_PUBLIC_IP}

ECR Repository URI   : ${ECR_REPOSITORY_URI}
S3 Bucket            : ${S3_BUCKET}

DB Secret ARN        : ${DB_SECRET_ARN}
App Secret ARN       : ${APP_SECRET_ARN}

CloudWatch Log Group : ${LOG_GROUP}

GitHub Role ARN      : ${GITHUB_ROLE_ARN}
EC2 Role ARN         : ${EC2_ROLE_ARN}
========================================

Next step:
- Set GITHUB_REPO to your org/repo before rerunning (current: ${GITHUB_REPO})
- Replace placeholder secret values in Secrets Manager.
EOF
}

main() {
  ensure_prerequisites
  ensure_cognito_user_pool_and_client
  ensure_ecr_repository
  ensure_s3_bucket
  ensure_cloudwatch_log_group
  ensure_secrets_manager_secrets
  ensure_iam_roles
  ensure_ec2_infrastructure
  print_outputs
}

main "$@"

