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
    key    = "vaultvibes/dev/terraform.tfstate"
    region = "us-east-1"
  }
}

provider "aws" {
  region = "us-east-1"

  default_tags {
    tags = {
      Project     = "vaultvibes"
      Environment = "dev"
      ManagedBy   = "terraform"
    }
  }
}

# ─── Data: Existing shared infrastructure ─────────────────────────────────────

data "aws_vpc" "default" {
  id = "vpc-075b3102672a9af26"
}

data "aws_lb" "alb" {
  name = "vault-vibes-backend-alb"
}

data "aws_lb_listener" "https" {
  load_balancer_arn = data.aws_lb.alb.arn
  port              = 443
}

data "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"
}

data "aws_route53_zone" "vaultvibes" {
  name = "vaultvibes.co.za."
}

# ─── ACM Certificate (dev subdomains) ─────────────────────────────────────────
# Covers frontend, API, and auth dev subdomains.
# Must be in us-east-1 for both ALB and CloudFront.

resource "aws_acm_certificate" "dev" {
  domain_name = "dev.vaultvibes.co.za"
  subject_alternative_names = [
    "dev-api.vaultvibes.co.za",
    "dev-auth.vaultvibes.co.za",
  ]
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "dev_cert_validation" {
  for_each = {
    for dvo in aws_acm_certificate.dev.domain_validation_options : dvo.domain_name => {
      name   = dvo.resource_record_name
      record = dvo.resource_record_value
      type   = dvo.resource_record_type
    }
  }

  allow_overwrite = true
  name            = each.value.name
  records         = [each.value.record]
  ttl             = 60
  type            = each.value.type
  zone_id         = data.aws_route53_zone.vaultvibes.zone_id
}

resource "aws_acm_certificate_validation" "dev" {
  certificate_arn         = aws_acm_certificate.dev.arn
  validation_record_fqdns = [for r in aws_route53_record.dev_cert_validation : r.fqdn]
}

# ─── Security Group: DEV ECS Tasks ────────────────────────────────────────────

resource "aws_security_group" "dev_ecs" {
  name        = "vaultvibes-dev-api-sg"
  description = "vaultvibes dev API ECS tasks - port 8080 from ALB only"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "HTTP from ALB"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = ["sg-05e73c6c4716d29eb"] # vault-vibes-alb-sg
  }

  egress {
    description = "All outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# Add dev cert to the shared ALB HTTPS listener (SNI-based)
resource "aws_lb_listener_certificate" "dev" {
  listener_arn    = data.aws_lb_listener.https.arn
  certificate_arn = aws_acm_certificate_validation.dev.certificate_arn
}

# ─── Target Group: DEV ────────────────────────────────────────────────────────

resource "aws_lb_target_group" "dev" {
  name        = "vault-vibes-dev-backend-tg"
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = data.aws_vpc.default.id

  health_check {
    path                = "/actuator/health"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 30
    timeout             = 10
    matcher             = "200"
  }
}

# ─── ALB Listener Rule: Host-based routing for dev-api ────────────────────────

resource "aws_lb_listener_rule" "dev_api" {
  listener_arn = data.aws_lb_listener.https.arn
  priority     = 10 # Lower number = higher priority; prod default action is lowest

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.dev.arn
  }

  condition {
    host_header {
      values = ["dev-api.vaultvibes.co.za"]
    }
  }
}

# ─── CloudWatch Log Group: DEV ────────────────────────────────────────────────

resource "aws_cloudwatch_log_group" "dev_backend" {
  name              = "/ecs/vault-dev-backend"
  retention_in_days = 14
}

# ─── Secrets Manager: DEV ─────────────────────────────────────────────────────

resource "aws_secretsmanager_secret" "dev_db" {
  name        = "vaultvibes/dev/backend/db"
  description = "Vault Vibes dev database credentials"
}

resource "aws_secretsmanager_secret_version" "dev_db" {
  secret_id = aws_secretsmanager_secret.dev_db.id
  secret_string = jsonencode({
    DB_URL      = "jdbc:postgresql://prod-omnisolve-postgres.cmrg4wcome3h.us-east-1.rds.amazonaws.com:5432/vaultvibes_dev"
    DB_USERNAME = "vaultvibes_dev_app"
    DB_PASSWORD = "REPLACE_WITH_SECURE_PASSWORD"
  })
  # Password is set manually after apply; ignore to prevent drift
  lifecycle {
    ignore_changes = [secret_string]
  }
}

resource "aws_secretsmanager_secret" "dev_app" {
  name        = "vaultvibes/dev/backend/app"
  description = "Vault Vibes dev app configuration"
}

resource "aws_secretsmanager_secret_version" "dev_app" {
  secret_id = aws_secretsmanager_secret.dev_app.id
  secret_string = jsonencode({
    APP_ENV   = "dev"
    S3_BUCKET = "vault-vibes-dev-uploads"
  })
  lifecycle {
    ignore_changes = [secret_string]
  }
}

# ─── IAM: ECS Task Execution Role (DEV) ───────────────────────────────────────

resource "aws_iam_role" "dev_ecs_exec" {
  name = "vaultvibes-dev-ecs-task-exec-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "dev_ecs_exec_managed" {
  role       = aws_iam_role.dev_ecs_exec.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "dev_ecs_exec_secrets" {
  name = "vaultvibes-dev-ecs-task-exec-inline"
  role = aws_iam_role.dev_ecs_exec.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid    = "ReadDevSecrets"
      Effect = "Allow"
      Action = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
      Resource = "arn:aws:secretsmanager:us-east-1:861870144419:secret:vaultvibes/dev/*"
    }]
  })
}

# ─── IAM: ECS Task Role (DEV) ─────────────────────────────────────────────────

resource "aws_iam_role" "dev_ecs_task" {
  name = "vaultvibes-dev-ecs-task-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "dev_ecs_task_inline" {
  name = "vaultvibes-dev-ecs-task-inline"
  role = aws_iam_role.dev_ecs_task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "ReadDevSecrets"
        Effect = "Allow"
        Action = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
        Resource = "arn:aws:secretsmanager:us-east-1:861870144419:secret:vaultvibes/dev/*"
      },
      {
        Sid    = "S3DevUploads"
        Effect = "Allow"
        Action = ["s3:ListBucket", "s3:GetObject", "s3:PutObject"]
        Resource = [
          "arn:aws:s3:::vault-vibes-dev-uploads",
          "arn:aws:s3:::vault-vibes-dev-uploads/*"
        ]
      },
      {
        Sid    = "EventBridgeDev"
        Effect = "Allow"
        Action = ["events:PutEvents"]
        Resource = "arn:aws:events:us-east-1:861870144419:event-bus/vault-vibes-dev-events"
      }
    ]
  })
}

# ─── IAM: GitHub Deploy Role — Backend (DEV) ──────────────────────────────────

resource "aws_iam_role" "dev_github_backend" {
  name = "vaultvibes-dev-github-deploy-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = data.aws_iam_openid_connect_provider.github.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
        }
        StringLike = {
          "token.actions.githubusercontent.com:sub" = "repo:SibusisoMashita/vault-vibes-backend:*"
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "dev_github_backend_inline" {
  name = "vaultvibes-dev-github-inline"
  role = aws_iam_role.dev_github_backend.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "EcrPushPull"
        Effect = "Allow"
        Action = [
          "ecr:GetAuthorizationToken",
          "ecr:BatchGetImage",
          "ecr:GetDownloadUrlForLayer",
          "ecr:BatchCheckLayerAvailability",
          "ecr:InitiateLayerUpload",
          "ecr:UploadLayerPart",
          "ecr:CompleteLayerUpload",
          "ecr:PutImage",
          "ecr:DescribeImages"
        ]
        Resource = ["arn:aws:ecr:us-east-1:861870144419:repository/vaultvibes-api", "*"]
      },
      {
        Sid    = "SecretsRead"
        Effect = "Allow"
        Action = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
        Resource = "arn:aws:secretsmanager:us-east-1:861870144419:secret:vaultvibes/dev/*"
      },
      {
        Sid    = "CloudWatchLogs"
        Effect = "Allow"
        Action = ["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"]
        Resource = [
          "arn:aws:logs:us-east-1:861870144419:log-group:/ecs/vault-dev-backend",
          "arn:aws:logs:us-east-1:861870144419:log-group:/ecs/vault-dev-backend:*"
        ]
      },
      {
        Sid    = "EcsDeploy"
        Effect = "Allow"
        Action = [
          "ecs:RegisterTaskDefinition",
          "ecs:DeregisterTaskDefinition",
          "ecs:DescribeTaskDefinition",
          "ecs:UpdateService",
          "ecs:DescribeServices",
          "ecs:DescribeClusters"
        ]
        Resource = "*"
      },
      {
        Sid    = "PassRoleToEcs"
        Effect = "Allow"
        Action = ["iam:PassRole"]
        Resource = "arn:aws:iam::861870144419:role/vaultvibes-dev-*"
        Condition = {
          StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" }
        }
      }
    ]
  })
}

# ─── IAM: GitHub Deploy Role — Frontend (DEV) ─────────────────────────────────

resource "aws_iam_role" "dev_github_frontend" {
  name = "vaultvibes-dev-frontend-github-deploy-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = data.aws_iam_openid_connect_provider.github.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
        }
        StringLike = {
          "token.actions.githubusercontent.com:sub" = "repo:SibusisoMashita/vault-vibes-frontend:*"
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "dev_github_frontend_inline" {
  name = "vaultvibes-dev-frontend-github-inline"
  role = aws_iam_role.dev_github_frontend.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "S3Deploy"
        Effect = "Allow"
        Action = ["s3:ListBucket", "s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
        Resource = [
          "arn:aws:s3:::vaultvibes-dev-frontend",
          "arn:aws:s3:::vaultvibes-dev-frontend/*"
        ]
      },
      {
        Sid    = "CloudFrontInvalidate"
        Effect = "Allow"
        Action = ["cloudfront:CreateInvalidation"]
        Resource = "arn:aws:cloudfront::861870144419:distribution/${aws_cloudfront_distribution.dev_frontend.id}"
      }
    ]
  })
}

# ─── S3: DEV Frontend Bucket ──────────────────────────────────────────────────

resource "aws_s3_bucket" "dev_frontend" {
  bucket = "vaultvibes-dev-frontend"
}

resource "aws_s3_bucket_versioning" "dev_frontend" {
  bucket = aws_s3_bucket.dev_frontend.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "dev_frontend" {
  bucket = aws_s3_bucket.dev_frontend.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}

resource "aws_s3_bucket_public_access_block" "dev_frontend" {
  bucket                  = aws_s3_bucket.dev_frontend.id
  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_policy" "dev_frontend" {
  bucket = aws_s3_bucket.dev_frontend.id
  # Policy depends on CloudFront distribution ARN - applied after CF is created
  depends_on = [aws_cloudfront_distribution.dev_frontend]

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "cloudfront.amazonaws.com" }
      Action    = "s3:GetObject"
      Resource  = "${aws_s3_bucket.dev_frontend.arn}/*"
      Condition = {
        StringEquals = {
          "AWS:SourceArn" = aws_cloudfront_distribution.dev_frontend.arn
        }
      }
    }]
  })
}

# ─── CloudFront OAC: DEV ──────────────────────────────────────────────────────

resource "aws_cloudfront_origin_access_control" "dev_frontend" {
  name                              = "vaultvibes-dev-frontend-oac"
  description                       = "OAC for vaultvibes dev frontend S3"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# ─── CloudFront: DEV Frontend ─────────────────────────────────────────────────

resource "aws_cloudfront_distribution" "dev_frontend" {
  comment             = "VaultVibes Dev Frontend SPA - dev.vaultvibes.co.za"
  enabled             = true
  is_ipv6_enabled     = true
  default_root_object = "index.html"
  aliases             = ["dev.vaultvibes.co.za"]

  origin {
    domain_name              = aws_s3_bucket.dev_frontend.bucket_regional_domain_name
    origin_id                = "vaultvibes-dev-frontend-s3"
    origin_access_control_id = aws_cloudfront_origin_access_control.dev_frontend.id
  }

  # Hashed assets (long TTL)
  default_cache_behavior {
    allowed_methods        = ["GET", "HEAD", "OPTIONS"]
    cached_methods         = ["GET", "HEAD"]
    target_origin_id       = "vaultvibes-dev-frontend-s3"
    viewer_protocol_policy = "redirect-to-https"
    compress               = true

    forwarded_values {
      query_string = false
      cookies { forward = "none" }
    }

    min_ttl     = 0
    default_ttl = 3600
    max_ttl     = 86400
  }

  # index.html — no cache so new deploys take effect immediately
  ordered_cache_behavior {
    path_pattern           = "/index.html"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    target_origin_id       = "vaultvibes-dev-frontend-s3"
    viewer_protocol_policy = "redirect-to-https"
    compress               = true

    forwarded_values {
      query_string = false
      cookies { forward = "none" }
    }

    min_ttl     = 0
    default_ttl = 0
    max_ttl     = 0
  }

  # SPA fallback: 404/403 → index.html (client-side routing)
  custom_error_response {
    error_code            = 404
    response_code         = 200
    response_page_path    = "/index.html"
    error_caching_min_ttl = 0
  }

  custom_error_response {
    error_code            = 403
    response_code         = 200
    response_page_path    = "/index.html"
    error_caching_min_ttl = 0
  }

  restrictions {
    geo_restriction { restriction_type = "none" }
  }

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.dev.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }
}

# ─── S3: DEV Uploads Bucket ───────────────────────────────────────────────────

resource "aws_s3_bucket" "dev_uploads" {
  bucket = "vault-vibes-dev-uploads"
}

resource "aws_s3_bucket_server_side_encryption_configuration" "dev_uploads" {
  bucket = aws_s3_bucket.dev_uploads.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}

resource "aws_s3_bucket_public_access_block" "dev_uploads" {
  bucket                  = aws_s3_bucket.dev_uploads.id
  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = true
  restrict_public_buckets = true
}

# ─── EventBridge: DEV ────────────────────────────────────────────────────────

resource "aws_cloudwatch_event_bus" "dev" {
  name = "vault-vibes-dev-events"
}

# ─── Cognito: DEV User Pool ───────────────────────────────────────────────────

resource "aws_iam_role" "dev_cognito_sms" {
  name = "vaultvibes-dev-cognito-sms-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "cognito-idp.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = {
        StringEquals = { "sts:ExternalId" = "vaultvibes-dev-861870144419-cognito-sms" }
        ArnLike     = { "aws:SourceArn" = "arn:aws:cognito-idp:us-east-1:861870144419:userpool/*" }
      }
    }]
  })
}

resource "aws_iam_role_policy" "dev_cognito_sms" {
  name   = "vaultvibes-dev-cognito-sms-inline"
  role   = aws_iam_role.dev_cognito_sms.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "sns:Publish"
      Resource = "*"
    }]
  })
}

resource "aws_cognito_user_pool" "dev" {
  name = "vaultvibes-dev-users"

  alias_attributes         = ["phone_number"]
  auto_verified_attributes = ["phone_number"]

  verification_message_template {
    sms_message = "Your Vault Vibes verification code is {####}"
  }

  sms_configuration {
    sns_caller_arn = aws_iam_role.dev_cognito_sms.arn
    external_id    = "vaultvibes-dev-861870144419-cognito-sms"
  }

  user_pool_add_ons {
    advanced_security_mode = "OFF"
  }
}

resource "aws_cognito_user_pool_client" "dev_api" {
  name         = "vaultvibes-dev-api-client"
  user_pool_id = aws_cognito_user_pool.dev.id

  generate_secret = true
  explicit_auth_flows = [
    "ALLOW_USER_PASSWORD_AUTH",
    "ALLOW_REFRESH_TOKEN_AUTH",
    "ALLOW_USER_SRP_AUTH"
  ]
  prevent_user_existence_errors = "ENABLED"
}

resource "aws_cognito_user_pool_client" "dev_spa" {
  name         = "vaultvibes-dev-spa-public"
  user_pool_id = aws_cognito_user_pool.dev.id

  generate_secret = false
  explicit_auth_flows = [
    "ALLOW_USER_PASSWORD_AUTH",
    "ALLOW_REFRESH_TOKEN_AUTH",
    "ALLOW_USER_SRP_AUTH",
    "ALLOW_CUSTOM_AUTH"
  ]

  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_scopes                 = ["openid", "email", "phone"]

  callback_urls                = ["https://dev.vaultvibes.co.za/callback"]
  logout_urls                  = ["https://dev.vaultvibes.co.za/"]
  supported_identity_providers = ["COGNITO"]
  prevent_user_existence_errors = "ENABLED"
}

# Cognito hosted UI domain (prefix-based; custom domain can be added later)
resource "aws_cognito_user_pool_domain" "dev" {
  domain       = "vaultvibes-dev-auth"
  user_pool_id = aws_cognito_user_pool.dev.id
}

# ─── ECS: Bootstrap Task Definition (DEV) ────────────────────────────────────
# Used only for initial ECS service creation.
# CI/CD registers new revisions on every deploy; ignore_changes prevents drift.

resource "aws_ecs_task_definition" "dev_bootstrap" {
  family                   = "vault-dev-backend-task"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.dev_ecs_exec.arn
  task_role_arn            = aws_iam_role.dev_ecs_task.arn

  container_definitions = jsonencode([{
    name      = "vault-backend"
    image     = "861870144419.dkr.ecr.us-east-1.amazonaws.com/vaultvibes-api:latest"
    essential = true
    portMappings = [{ containerPort = 8080, protocol = "tcp" }]
    environment = [{ name = "SPRING_PROFILES_ACTIVE", value = "dev" }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = "/ecs/vault-dev-backend"
        "awslogs-region"        = "us-east-1"
        "awslogs-stream-prefix" = "ecs"
      }
    }
  }])

  lifecycle {
    # CI/CD owns task definition revisions after initial creation
    ignore_changes = [container_definitions]
  }

  depends_on = [aws_cloudwatch_log_group.dev_backend]
}

# ─── ECS Service: DEV ─────────────────────────────────────────────────────────

resource "aws_ecs_service" "dev" {
  name            = "vault-dev-backend-service"
  cluster         = "arn:aws:ecs:us-east-1:861870144419:cluster/vault-vibes-cluster"
  task_definition = aws_ecs_task_definition.dev_bootstrap.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets = [
      "subnet-00e96575053938e20",
      "subnet-02467c3d3eca10368",
      "subnet-015e73eb696f8577c",
    ]
    security_groups  = [aws_security_group.dev_ecs.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.dev.arn
    container_name   = "vault-backend"
    container_port   = 8080
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  deployment_maximum_percent         = 200
  deployment_minimum_healthy_percent = 100

  lifecycle {
    # CI/CD updates task_definition on every deploy
    ignore_changes = [task_definition, desired_count]
  }

  depends_on = [aws_lb_listener_rule.dev_api, aws_ecs_task_definition.dev_bootstrap]
}

# ─── Route53: DEV Records ─────────────────────────────────────────────────────

resource "aws_route53_record" "dev_frontend" {
  zone_id = data.aws_route53_zone.vaultvibes.zone_id
  name    = "dev.vaultvibes.co.za"
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.dev_frontend.domain_name
    zone_id                = aws_cloudfront_distribution.dev_frontend.hosted_zone_id
    evaluate_target_health = false
  }
}

resource "aws_route53_record" "dev_api" {
  zone_id = data.aws_route53_zone.vaultvibes.zone_id
  name    = "dev-api.vaultvibes.co.za"
  type    = "A"

  alias {
    name                   = data.aws_lb.alb.dns_name
    zone_id                = data.aws_lb.alb.zone_id
    evaluate_target_health = true
  }
}