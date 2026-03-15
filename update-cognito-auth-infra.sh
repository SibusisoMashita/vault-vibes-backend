#!/usr/bin/env bash
set -euo pipefail

REGION="us-east-1"
USER_POOL_ID="us-east-1_Pmg4WjBdm"
APP_CLIENT_ID="3qsigtvtgu0jm2h0qdb2cthfh2"
DEFAULT_HOSTED_DOMAIN="vaultvibes-auth"

REQUIRED_CALLBACK_URLS=(
  "http://localhost:5173"
  "http://localhost:5173/auth/callback"
)

REQUIRED_LOGOUT_URLS=(
  "http://localhost:5173"
)

REQUIRED_OAUTH_FLOWS=(
  "code"
)

REQUIRED_OAUTH_SCOPES=(
  "openid"
  "profile"
  "email"
)

info() {
  echo "[INFO] $*"
}

fail() {
  echo "[ERROR] $*" >&2
  exit 1
}

aws_cli() {
  aws --region "$REGION" "$@"
}

append_unique() {
  local list="$1"
  local item="$2"

  case " $list " in
    *" $item "*)
      printf '%s' "$list"
      ;;
    *)
      if [[ -n "$list" ]]; then
        printf '%s %s' "$list" "$item"
      else
        printf '%s' "$item"
      fi
      ;;
  esac
}

contains_word() {
  local list="$1"
  local word="$2"
  case " $list " in
    *" $word "*) return 0 ;;
    *) return 1 ;;
  esac
}

query_list() {
  local raw="$1"
  if [[ -z "$raw" || "$raw" == "None" ]]; then
    printf ''
  else
    printf '%s' "$raw"
  fi
}

ensure_prerequisites() {
  info "Validating prerequisites..."

  if ! command -v aws >/dev/null 2>&1; then
    fail "AWS CLI is not installed."
  fi

  if ! aws sts get-caller-identity >/dev/null 2>&1; then
    fail "AWS authentication failed. Run aws configure / aws sso login first."
  fi

  local configured_region
  configured_region="${AWS_REGION:-${AWS_DEFAULT_REGION:-$(aws configure get region 2>/dev/null || true)}}"

  if [[ -z "$configured_region" ]]; then
    fail "No default AWS region is configured. Expected region: ${REGION}."
  fi

  if [[ "$configured_region" != "$REGION" ]]; then
    fail "Configured AWS region is '${configured_region}', expected '${REGION}'."
  fi

  info "Prerequisites OK."
}

inspect_user_pool() {
  info "Inspecting Cognito User Pool..."

  POOL_NAME=$(aws_cli cognito-idp describe-user-pool \
    --user-pool-id "$USER_POOL_ID" \
    --query 'UserPool.Name' \
    --output text)

  MFA_CONFIGURATION=$(aws_cli cognito-idp describe-user-pool \
    --user-pool-id "$USER_POOL_ID" \
    --query 'UserPool.MfaConfiguration' \
    --output text)

  AUTO_VERIFIED_ATTRIBUTES=$(query_list "$(aws_cli cognito-idp describe-user-pool \
    --user-pool-id "$USER_POOL_ID" \
    --query 'UserPool.AutoVerifiedAttributes[]' \
    --output text)")

  USERNAME_ATTRIBUTES=$(query_list "$(aws_cli cognito-idp describe-user-pool \
    --user-pool-id "$USER_POOL_ID" \
    --query 'UserPool.UsernameAttributes[]' \
    --output text)")

  ALIAS_ATTRIBUTES=$(query_list "$(aws_cli cognito-idp describe-user-pool \
    --user-pool-id "$USER_POOL_ID" \
    --query 'UserPool.AliasAttributes[]' \
    --output text)")

  PASSWORD_POLICY=$(aws_cli cognito-idp describe-user-pool \
    --user-pool-id "$USER_POOL_ID" \
    --query 'UserPool.Policies.PasswordPolicy' \
    --output json)

  CURRENT_POOL_DOMAIN=$(aws_cli cognito-idp describe-user-pool \
    --user-pool-id "$USER_POOL_ID" \
    --query 'UserPool.Domain' \
    --output text)

  if [[ "$CURRENT_POOL_DOMAIN" == "None" ]]; then
    CURRENT_POOL_DOMAIN=""
  fi

  echo
  echo "User Pool Name           : ${POOL_NAME}"
  echo "MFA Configuration        : ${MFA_CONFIGURATION}"
  echo "Auto Verified Attributes : ${AUTO_VERIFIED_ATTRIBUTES:-<none>}"
  echo "Username Attributes      : ${USERNAME_ATTRIBUTES:-<none>}"
  echo "Alias Attributes         : ${ALIAS_ATTRIBUTES:-<none>}"
  echo "Password Policy          : ${PASSWORD_POLICY}"
  echo

  if contains_word "$USERNAME_ATTRIBUTES" "phone_number" || contains_word "$ALIAS_ATTRIBUTES" "phone_number"; then
    info "Verified: phone_number login is enabled."
  else
    fail "phone_number login is not enabled on this user pool."
  fi
}

inspect_app_client() {
  info "Inspecting App Client configuration..."

  APP_CLIENT_NAME=$(aws_cli cognito-idp describe-user-pool-client \
    --user-pool-id "$USER_POOL_ID" \
    --client-id "$APP_CLIENT_ID" \
    --query 'UserPoolClient.ClientName' \
    --output text)

  EXISTING_OAUTH_FLOWS=$(query_list "$(aws_cli cognito-idp describe-user-pool-client \
    --user-pool-id "$USER_POOL_ID" \
    --client-id "$APP_CLIENT_ID" \
    --query 'UserPoolClient.AllowedOAuthFlows[]' \
    --output text)")

  EXISTING_OAUTH_SCOPES=$(query_list "$(aws_cli cognito-idp describe-user-pool-client \
    --user-pool-id "$USER_POOL_ID" \
    --client-id "$APP_CLIENT_ID" \
    --query 'UserPoolClient.AllowedOAuthScopes[]' \
    --output text)")

  EXISTING_CALLBACK_URLS=$(query_list "$(aws_cli cognito-idp describe-user-pool-client \
    --user-pool-id "$USER_POOL_ID" \
    --client-id "$APP_CLIENT_ID" \
    --query 'UserPoolClient.CallbackURLs[]' \
    --output text)")

  EXISTING_LOGOUT_URLS=$(query_list "$(aws_cli cognito-idp describe-user-pool-client \
    --user-pool-id "$USER_POOL_ID" \
    --client-id "$APP_CLIENT_ID" \
    --query 'UserPoolClient.LogoutURLs[]' \
    --output text)")

  OAUTH_FLOWS_ENABLED=$(aws_cli cognito-idp describe-user-pool-client \
    --user-pool-id "$USER_POOL_ID" \
    --client-id "$APP_CLIENT_ID" \
    --query 'UserPoolClient.AllowedOAuthFlowsUserPoolClient' \
    --output text)

  SUPPORTED_IDENTITY_PROVIDERS=$(query_list "$(aws_cli cognito-idp describe-user-pool-client \
    --user-pool-id "$USER_POOL_ID" \
    --client-id "$APP_CLIENT_ID" \
    --query 'UserPoolClient.SupportedIdentityProviders[]' \
    --output text)")

  if [[ -z "$SUPPORTED_IDENTITY_PROVIDERS" ]]; then
    SUPPORTED_IDENTITY_PROVIDERS="COGNITO"
  fi

  echo
  echo "App Client Name                : ${APP_CLIENT_NAME}"
  echo "Allowed OAuth Flows            : ${EXISTING_OAUTH_FLOWS:-<none>}"
  echo "Allowed OAuth Scopes           : ${EXISTING_OAUTH_SCOPES:-<none>}"
  echo "OAuth Flows User Pool Client   : ${OAUTH_FLOWS_ENABLED}"
  echo "Callback URLs                  : ${EXISTING_CALLBACK_URLS:-<none>}"
  echo "Logout URLs                    : ${EXISTING_LOGOUT_URLS:-<none>}"
  echo
}

ensure_hosted_ui_domain() {
  info "Ensuring Hosted UI domain exists..."

  local candidate_domain="${DEFAULT_HOSTED_DOMAIN}"

  if [[ -n "$CURRENT_POOL_DOMAIN" ]]; then
    HOSTED_DOMAIN="$CURRENT_POOL_DOMAIN"
    info "User pool already has domain prefix: ${HOSTED_DOMAIN}"
    return
  fi

  local owner_pool_id
  owner_pool_id=$(aws_cli cognito-idp describe-user-pool-domain \
    --domain "$candidate_domain" \
    --query 'DomainDescription.UserPoolId' \
    --output text 2>/dev/null || true)

  if [[ -z "$owner_pool_id" || "$owner_pool_id" == "None" ]]; then
    aws_cli cognito-idp create-user-pool-domain \
      --domain "$candidate_domain" \
      --user-pool-id "$USER_POOL_ID" >/dev/null
    info "Created Hosted UI domain: ${candidate_domain}"
    HOSTED_DOMAIN="$candidate_domain"
  elif [[ "$owner_pool_id" == "$USER_POOL_ID" ]]; then
    info "Hosted UI domain already exists: ${candidate_domain}"
    HOSTED_DOMAIN="$candidate_domain"
  else
    fail "Domain '${candidate_domain}' already belongs to another user pool (${owner_pool_id})."
  fi
}

ensure_app_client_oauth_configuration() {
  info "Ensuring callback/logout URLs and OAuth configuration..."

  local merged_callbacks="$EXISTING_CALLBACK_URLS"
  local merged_logouts="$EXISTING_LOGOUT_URLS"
  local merged_flows="$EXISTING_OAUTH_FLOWS"
  local merged_scopes="$EXISTING_OAUTH_SCOPES"

  local needs_update="false"

  local item
  for item in "${REQUIRED_CALLBACK_URLS[@]}"; do
    if ! contains_word "$merged_callbacks" "$item"; then
      merged_callbacks=$(append_unique "$merged_callbacks" "$item")
      needs_update="true"
    fi
  done

  for item in "${REQUIRED_LOGOUT_URLS[@]}"; do
    if ! contains_word "$merged_logouts" "$item"; then
      merged_logouts=$(append_unique "$merged_logouts" "$item")
      needs_update="true"
    fi
  done

  for item in "${REQUIRED_OAUTH_FLOWS[@]}"; do
    if ! contains_word "$merged_flows" "$item"; then
      merged_flows=$(append_unique "$merged_flows" "$item")
      needs_update="true"
    fi
  done

  for item in "${REQUIRED_OAUTH_SCOPES[@]}"; do
    if ! contains_word "$merged_scopes" "$item"; then
      merged_scopes=$(append_unique "$merged_scopes" "$item")
      needs_update="true"
    fi
  done

  if [[ "$OAUTH_FLOWS_ENABLED" != "True" ]]; then
    needs_update="true"
  fi

  local callbacks_array=()
  local logouts_array=()
  local flows_array=()
  local scopes_array=()
  local idp_array=()

  if [[ -n "$merged_callbacks" ]]; then
    # shellcheck disable=SC2206
    callbacks_array=($merged_callbacks)
  fi
  if [[ -n "$merged_logouts" ]]; then
    # shellcheck disable=SC2206
    logouts_array=($merged_logouts)
  fi
  if [[ -n "$merged_flows" ]]; then
    # shellcheck disable=SC2206
    flows_array=($merged_flows)
  fi
  if [[ -n "$merged_scopes" ]]; then
    # shellcheck disable=SC2206
    scopes_array=($merged_scopes)
  fi
  if [[ -n "$SUPPORTED_IDENTITY_PROVIDERS" ]]; then
    # shellcheck disable=SC2206
    idp_array=($SUPPORTED_IDENTITY_PROVIDERS)
  fi

  if [[ "$needs_update" == "true" ]]; then
    aws_cli cognito-idp update-user-pool-client \
      --user-pool-id "$USER_POOL_ID" \
      --client-id "$APP_CLIENT_ID" \
      --allowed-o-auth-flows-user-pool-client \
      --allowed-o-auth-flows "${flows_array[@]}" \
      --allowed-o-auth-scopes "${scopes_array[@]}" \
      --callback-urls "${callbacks_array[@]}" \
      --logout-urls "${logouts_array[@]}" \
      --supported-identity-providers "${idp_array[@]}" >/dev/null
    info "Updated app client OAuth settings."
  else
    info "App client OAuth settings already satisfy requirements."
  fi

  EXISTING_CALLBACK_URLS="$merged_callbacks"
  EXISTING_LOGOUT_URLS="$merged_logouts"
  EXISTING_OAUTH_FLOWS="$merged_flows"
  EXISTING_OAUTH_SCOPES="$merged_scopes"
  OAUTH_FLOWS_ENABLED="True"

  if ! contains_word "$EXISTING_OAUTH_FLOWS" "code"; then
    fail "Authorization Code Flow (code) is missing after update."
  fi

  for item in "openid" "profile" "email"; do
    if ! contains_word "$EXISTING_OAUTH_SCOPES" "$item"; then
      fail "Required OAuth scope '${item}' is missing after update."
    fi
  done
}

generate_urls() {
  LOGIN_URL="https://${HOSTED_DOMAIN}.auth.${REGION}.amazoncognito.com/login?client_id=${APP_CLIENT_ID}&response_type=code&scope=openid+profile+email&redirect_uri=http://localhost:5173/auth/callback"
  LOGOUT_URL="https://${HOSTED_DOMAIN}.auth.${REGION}.amazoncognito.com/logout?client_id=${APP_CLIENT_ID}&logout_uri=http://localhost:5173"
}

print_summary() {
  echo
  echo "---"
  echo "## Vault Vibes Cognito Configuration"
  echo
  echo "User Pool ID: ${USER_POOL_ID}"
  echo "App Client ID: ${APP_CLIENT_ID}"
  echo "Hosted Domain: ${HOSTED_DOMAIN}"
  echo "Login URL: ${LOGIN_URL}"
  echo "Logout URL: ${LOGOUT_URL}"
  echo "---"
  echo
}

main() {
  ensure_prerequisites
  inspect_user_pool
  inspect_app_client
  ensure_hosted_ui_domain
  ensure_app_client_oauth_configuration
  generate_urls
  print_summary
}

main "$@"

