terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  backend "s3" {
    bucket = "omnisolve-terraform-state"
    key    = "vaultvibes/prod/terraform.tfstate"
    region = "us-east-1"
  }
}

provider "aws" {
  region = "us-east-1"

  default_tags {
    tags = {
      Project     = "vaultvibes"
      Environment = "prod"
      ManagedBy   = "terraform"
    }
  }
}

# ─── PROD Resources ───────────────────────────────────────────────────────────
# Most resources correspond to existing AWS infrastructure — run import.sh first.
# Exception: aws_iam_role.prod_github_frontend_deploy is a NEW resource;
# Terraform will create it on first apply (no import needed).

# ─── Networking ───────────────────────────────────────────────────────────────

data "aws_vpc" "default" {
  id = "vpc-075b3102672a9af26"
}

data "aws_route53_zone" "vaultvibes" {
  name = "vaultvibes.co.za."
}

# ─── Security Groups ──────────────────────────────────────────────────────────

resource "aws_security_group" "alb" {
  name        = "vault-vibes-alb-sg"
  description = "VaultVibes prod ALB - public HTTP/HTTPS"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

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

resource "aws_security_group" "ecs" {
  name        = "vaultvibes-prod-api-sg"
  description = "vaultvibes prod API ECS tasks"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
    description     = "HTTP from ALB"
  }

  # SECURITY NOTE: rules below should be reviewed/removed in Phase 1
  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # SECURITY RISK: SSH open to world — Fargate needs no SSH; remove this rule
  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
    description = "REMOVE THIS RULE - SSH not needed on Fargate tasks"
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# ─── ACM Certificate ──────────────────────────────────────────────────────────

resource "aws_acm_certificate" "prod" {
  domain_name               = "vaultvibes.co.za"
  subject_alternative_names = ["api.vaultvibes.co.za"]
  validation_method         = "DNS"

  lifecycle { create_before_destroy = true }
}

# ─── ALB ──────────────────────────────────────────────────────────────────────

resource "aws_lb" "prod" {
  name               = "vault-vibes-backend-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets = [
    "subnet-00e96575053938e20",
    "subnet-02467c3d3eca10368",
    "subnet-015e73eb696f8577c",
    "subnet-030bd63d9e7059c69",
    "subnet-048004a221680c742",
    "subnet-03799209937f32dab",
  ]
  ip_address_type = "ipv4"
}

resource "aws_lb_target_group" "prod" {
  name        = "vault-vibes-backend-tg"
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = data.aws_vpc.default.id

  health_check {
    path                = "/actuator/health"
    healthy_threshold   = 2
    unhealthy_threshold = 2
    interval            = 30
    timeout             = 5
    matcher             = "200"
  }
}

resource "aws_lb_listener" "http_redirect" {
  load_balancer_arn = aws_lb.prod.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"
    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.prod.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = "arn:aws:acm:us-east-1:861870144419:certificate/aefc95d7-997e-444e-be27-6ffc9bfaae36"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.prod.arn
  }
}

# ─── ECR ──────────────────────────────────────────────────────────────────────

resource "aws_ecr_repository" "api" {
  name                 = "vaultvibes-api"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration { scan_on_push = true }
}

# ─── CloudWatch Log Group ─────────────────────────────────────────────────────

resource "aws_cloudwatch_log_group" "prod_backend" {
  name              = "/ecs/vault-backend"
  retention_in_days = 30
}

# ─── Secrets Manager ──────────────────────────────────────────────────────────

resource "aws_secretsmanager_secret" "prod_db" {
  name        = "vaultvibes/prod/db"
  description = "Vault Vibes production database credentials"
}

resource "aws_secretsmanager_secret" "prod_app" {
  name        = "vaultvibes/prod/app"
  description = "Vault Vibes production app configuration"
}

# ─── IAM: ECS Execution Role ──────────────────────────────────────────────────

resource "aws_iam_role" "prod_ecs_exec" {
  name = "vaultvibes-prod-ecs-task-exec-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "prod_ecs_exec_managed" {
  role       = aws_iam_role.prod_ecs_exec.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "prod_ecs_exec_secrets" {
  name = "vaultvibes-prod-ecs-task-exec-inline"
  role = aws_iam_role.prod_ecs_exec.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid    = "ReadProdSecrets"
      Effect = "Allow"
      Action = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
      Resource = "arn:aws:secretsmanager:us-east-1:861870144419:secret:vaultvibes/prod/*"
    }]
  })
}

# ─── IAM: ECS Task Role ───────────────────────────────────────────────────────

resource "aws_iam_role" "prod_ecs_task" {
  name = "vaultvibes-prod-ecs-task-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "prod_ecs_task_inline" {
  name = "vaultvibes-prod-ecs-task-inline"
  role = aws_iam_role.prod_ecs_task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "ReadSecrets"
        Effect = "Allow"
        Action = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
        Resource = "arn:aws:secretsmanager:us-east-1:861870144419:secret:vaultvibes/prod/*"
      },
      {
        Sid    = "S3Access"
        Effect = "Allow"
        Action = ["s3:ListBucket", "s3:GetObject", "s3:PutObject"]
        Resource = ["arn:aws:s3:::vault-vibes-uploads", "arn:aws:s3:::vault-vibes-uploads/*"]
      }
    ]
  })
}

# ─── IAM: GitHub Deploy Role ──────────────────────────────────────────────────

resource "aws_iam_role" "prod_github_deploy" {
  name = "vaultvibes-prod-github-deploy-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = "arn:aws:iam::861870144419:oidc-provider/token.actions.githubusercontent.com" }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = { "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com" }
        StringLike   = { "token.actions.githubusercontent.com:sub" = "repo:SibusisoMashita/vault-vibes-backend:*" }
      }
    }]
  })
}

resource "aws_iam_role_policy" "prod_github_inline" {
  name = "vaultvibes-prod-github-inline"
  role = aws_iam_role.prod_github_deploy.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "EcrPushPull"
        Effect = "Allow"
        Action = [
          "ecr:GetAuthorizationToken", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer",
          "ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart",
          "ecr:CompleteLayerUpload", "ecr:PutImage", "ecr:DescribeImages"
        ]
        Resource = ["arn:aws:ecr:us-east-1:861870144419:repository/vaultvibes-api", "*"]
      },
      {
        Sid    = "SecretsRead"
        Effect = "Allow"
        Action = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
        Resource = "arn:aws:secretsmanager:us-east-1:861870144419:secret:vaultvibes/prod/*"
      },
      {
        Sid    = "CloudWatchLogs"
        Effect = "Allow"
        Action = ["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"]
        Resource = [
          "arn:aws:logs:us-east-1:861870144419:log-group:/ecs/vault-backend",
          "arn:aws:logs:us-east-1:861870144419:log-group:/ecs/vault-backend:*"
        ]
      },
      {
        Sid    = "EcsDeploy"
        Effect = "Allow"
        Action = [
          "ecs:RegisterTaskDefinition", "ecs:DeregisterTaskDefinition",
          "ecs:DescribeTaskDefinition", "ecs:UpdateService",
          "ecs:DescribeServices", "ecs:DescribeClusters"
        ]
        Resource = "*"
      },
      {
        Sid    = "PassRoleToEcs"
        Effect = "Allow"
        Action = ["iam:PassRole"]
        Resource = "arn:aws:iam::861870144419:role/*"
        Condition = { StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" } }
      }
    ]
  })
}

# ─── IAM: GitHub Frontend Deploy Role ────────────────────────────────────────

resource "aws_iam_role" "prod_github_frontend_deploy" {
  name = "vaultvibes-prod-frontend-github-deploy-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = "arn:aws:iam::861870144419:oidc-provider/token.actions.githubusercontent.com" }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = { "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com" }
        StringLike   = { "token.actions.githubusercontent.com:sub" = "repo:SibusisoMashita/vault-vibes-frontend:*" }
      }
    }]
  })
}

resource "aws_iam_role_policy" "prod_github_frontend_inline" {
  name = "vaultvibes-prod-github-frontend-inline"
  role = aws_iam_role.prod_github_frontend_deploy.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "S3Deploy"
        Effect = "Allow"
        Action = [
          "s3:PutObject",
          "s3:GetObject",
          "s3:DeleteObject",
          "s3:ListBucket",
        ]
        Resource = [
          "arn:aws:s3:::vaultvibes-frontend",
          "arn:aws:s3:::vaultvibes-frontend/*",
        ]
      },
      {
        Sid      = "CloudFrontInvalidate"
        Effect   = "Allow"
        Action   = ["cloudfront:CreateInvalidation", "cloudfront:GetInvalidation"]
        Resource = "arn:aws:cloudfront::861870144419:distribution/E2LTR00BB2MIAY"
      },
      {
        Sid      = "S3HeadObject"
        Effect   = "Allow"
        Action   = ["s3:GetBucketLocation"]
        Resource = "arn:aws:s3:::vaultvibes-frontend"
      }
    ]
  })
}

# ─── S3: Frontend ─────────────────────────────────────────────────────────────

resource "aws_s3_bucket" "prod_frontend" {
  bucket = "vaultvibes-frontend"
}

resource "aws_s3_bucket_public_access_block" "prod_frontend" {
  bucket                  = aws_s3_bucket.prod_frontend.id
  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket" "prod_uploads" {
  bucket = "vault-vibes-uploads"
}

# ─── CloudFront: Prod Frontend ────────────────────────────────────────────────

resource "aws_cloudfront_distribution" "prod_frontend" {
  comment             = "VaultVibes Frontend SPA - vaultvibes.co.za"
  enabled             = true
  is_ipv6_enabled     = true
  default_root_object = "index.html"
  aliases             = ["vaultvibes.co.za"]

  origin {
    domain_name = aws_s3_bucket.prod_frontend.bucket_regional_domain_name
    origin_id   = "vaultvibes-frontend-s3"
  }

  default_cache_behavior {
    allowed_methods        = ["GET", "HEAD", "OPTIONS"]
    cached_methods         = ["GET", "HEAD"]
    target_origin_id       = "vaultvibes-frontend-s3"
    viewer_protocol_policy = "redirect-to-https"
    compress               = true
    forwarded_values {
      query_string = false
      cookies { forward = "none" }
    }
  }

  custom_error_response {
    error_code         = 404
    response_code      = 200
    response_page_path = "/index.html"
  }

  restrictions {
    geo_restriction { restriction_type = "none" }
  }

  viewer_certificate {
    acm_certificate_arn      = "arn:aws:acm:us-east-1:861870144419:certificate/0d7ff8c3-631b-4cbd-ae6c-21a0e4095d6d"
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }
}

# ─── Cognito ──────────────────────────────────────────────────────────────────

resource "aws_cognito_user_pool" "prod" {
  name = "vaultvibes-prod-users"

  alias_attributes         = ["phone_number"]
  auto_verified_attributes = ["phone_number"]

  verification_message_template {
    sms_message = "Your Vault Vibes verification code is {####}"
  }
}

resource "aws_cognito_user_pool_client" "prod_api" {
  name         = "vaultvibes-prod-api-client"
  user_pool_id = aws_cognito_user_pool.prod.id
  generate_secret = true
}

resource "aws_cognito_user_pool_client" "prod_spa" {
  name         = "vaultvibes-spa-public"
  user_pool_id = aws_cognito_user_pool.prod.id
  generate_secret = false
}

resource "aws_cognito_user_pool_domain" "prod" {
  domain       = "vaultvibes-auth"
  user_pool_id = aws_cognito_user_pool.prod.id
}

# ─── Route53 ──────────────────────────────────────────────────────────────────

resource "aws_route53_record" "prod_frontend" {
  zone_id = data.aws_route53_zone.vaultvibes.zone_id
  name    = "vaultvibes.co.za"
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.prod_frontend.domain_name
    zone_id                = aws_cloudfront_distribution.prod_frontend.hosted_zone_id
    evaluate_target_health = false
  }
}

resource "aws_route53_record" "prod_api" {
  zone_id = data.aws_route53_zone.vaultvibes.zone_id
  name    = "api.vaultvibes.co.za"
  type    = "A"

  alias {
    name                   = aws_lb.prod.dns_name
    zone_id                = aws_lb.prod.zone_id
    evaluate_target_health = true
  }
}