#!/bin/bash

# ============================================================================
# Keycloak Realm Initialization — Token Exchange Example
# ============================================================================
#
# Creates two realms to demonstrate the token exchange flow:
#
#   example-service-realm  →  where users log in (direct access)
#   gateway-realm          →  realm the gateway exchanges tokens to
#
# Token Exchange Flow:
#   1. User authenticates against example-service-realm → gets realm token
#   2. Client calls API Gateway with that token
#   3. Gateway exchanges it for a gateway-realm token
#      (using Keycloak's token exchange grant type)
#   4. Gateway forwards the gateway-realm token to example-service
#   5. example-service validates it (trusts both realms)
#
# ============================================================================

set -e

# ─── Configuration ─────────────────────────────────────────────────────────────
KEYCLOAK_URL="${KEYCLOAK_URL:-http://keycloak:8080}"
ADMIN_USER="${KEYCLOAK_ADMIN:-admin}"
ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-admin-secret}"

EXAMPLE_REALM="${EXAMPLE_SERVICE_REALM:-example-service-realm}"
GATEWAY_REALM="${GATEWAY_REALM:-gateway-realm}"

EXAMPLE_CLIENT_SECRET="${EXAMPLE_SERVICE_CLIENT_SECRET:-example-service-secret}"
GATEWAY_CLIENT_SECRET="${GATEWAY_CLIENT_SECRET:-gateway-client-secret}"

# Issuer URIs used when configuring the Identity Provider
EXAMPLE_ISSUER="${KEYCLOAK_BASE_URL:-http://keycloak:8080}/realms/${EXAMPLE_REALM}"
GATEWAY_ISSUER="${KEYCLOAK_BASE_URL:-http://keycloak:8080}/realms/${GATEWAY_REALM}"

# ─── Colors ────────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_header() { echo -e "\n${BLUE}══════════════════════════════════════════${NC}"; echo -e "${BLUE}  $1${NC}"; echo -e "${BLUE}══════════════════════════════════════════${NC}\n"; }
log_ok()     { echo -e "${GREEN}  ✓ $1${NC}"; }
log_warn()   { echo -e "${YELLOW}  ⚠ $1${NC}"; }
log_err()    { echo -e "${RED}  ✗ $1${NC}"; }
log_info()   { echo -e "  → $1"; }

# ─── Wait for Keycloak ─────────────────────────────────────────────────────────
wait_for_keycloak() {
    log_header "Waiting for Keycloak"
    local max=60 attempt=0

    while [ $attempt -lt $max ]; do
        if curl -sf "${KEYCLOAK_URL}/health/ready" > /dev/null 2>&1; then
            log_ok "Keycloak is ready at ${KEYCLOAK_URL}"
            return 0
        fi
        attempt=$((attempt + 1))
        log_info "Attempt $attempt/$max — waiting 5s..."
        sleep 5
    done

    log_err "Keycloak did not become ready in time"
    exit 1
}

# ─── Admin token ───────────────────────────────────────────────────────────────
TOKEN=""
get_admin_token() {
    TOKEN=$(curl -sf -X POST "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
        -H "Content-Type: application/x-www-form-urlencoded" \
        -d "username=${ADMIN_USER}" \
        -d "password=${ADMIN_PASSWORD}" \
        -d "grant_type=password" \
        -d "client_id=admin-cli" \
        | jq -r '.access_token')

    if [ -z "$TOKEN" ] || [ "$TOKEN" = "null" ]; then
        log_err "Failed to obtain admin token"
        exit 1
    fi
    log_ok "Admin token obtained"
}

# ─── Helpers ───────────────────────────────────────────────────────────────────
realm_exists() {
    local realm=$1
    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" \
        -H "Authorization: Bearer ${TOKEN}" \
        "${KEYCLOAK_URL}/admin/realms/${realm}")
    [ "$code" = "200" ]
}

client_uuid() {
    local realm=$1 client_id=$2
    curl -sf \
        -H "Authorization: Bearer ${TOKEN}" \
        "${KEYCLOAK_URL}/admin/realms/${realm}/clients?clientId=${client_id}" \
        | jq -r '.[0].id // empty'
}

# ─── Create realm ──────────────────────────────────────────────────────────────
create_realm() {
    local realm=$1
    log_header "Realm: ${realm}"

    if realm_exists "$realm"; then
        log_warn "Realm ${realm} already exists — skipping"
        return 0
    fi

    curl -sf -X POST "${KEYCLOAK_URL}/admin/realms" \
        -H "Authorization: Bearer ${TOKEN}" \
        -H "Content-Type: application/json" \
        -d "{
            \"realm\": \"${realm}\",
            \"enabled\": true,
            \"displayName\": \"${realm}\",
            \"accessTokenLifespan\": 300,
            \"sslRequired\": \"none\",
            \"registrationAllowed\": false,
            \"loginWithEmailAllowed\": true,
            \"bruteForceProtected\": true
        }"
    log_ok "Realm ${realm} created"
}

# ─── Create client ─────────────────────────────────────────────────────────────
create_client() {
    local realm=$1 client_id=$2 secret=$3
    log_info "Creating client '${client_id}' in realm '${realm}'"

    local uuid
    uuid=$(client_uuid "$realm" "$client_id")
    if [ -n "$uuid" ]; then
        log_warn "Client ${client_id} already exists — skipping"
        return 0
    fi

    curl -sf -X POST "${KEYCLOAK_URL}/admin/realms/${realm}/clients" \
        -H "Authorization: Bearer ${TOKEN}" \
        -H "Content-Type: application/json" \
        -d "{
            \"clientId\": \"${client_id}\",
            \"enabled\": true,
            \"protocol\": \"openid-connect\",
            \"publicClient\": false,
            \"serviceAccountsEnabled\": true,
            \"directAccessGrantsEnabled\": true,
            \"standardFlowEnabled\": true,
            \"secret\": \"${secret}\",
            \"redirectUris\": [\"*\"],
            \"webOrigins\": [\"*\"],
            \"attributes\": {
                \"access.token.lifespan\": \"300\"
            }
        }"
    log_ok "Client ${client_id} created"
}

# ─── Create test user ──────────────────────────────────────────────────────────
create_test_user() {
    local realm=$1 username=$2 password=$3 email=$4
    log_info "Creating user '${username}' in realm '${realm}'"

    local existing
    existing=$(curl -sf \
        -H "Authorization: Bearer ${TOKEN}" \
        "${KEYCLOAK_URL}/admin/realms/${realm}/users?username=${username}" \
        | jq -r '.[0].id // empty')

    if [ -n "$existing" ]; then
        log_warn "User ${username} already exists — skipping"
        return 0
    fi

    curl -sf -X POST "${KEYCLOAK_URL}/admin/realms/${realm}/users" \
        -H "Authorization: Bearer ${TOKEN}" \
        -H "Content-Type: application/json" \
        -d "{
            \"username\": \"${username}\",
            \"email\": \"${email}\",
            \"enabled\": true,
            \"emailVerified\": true,
            \"firstName\": \"Test\",
            \"lastName\": \"User\",
            \"credentials\": [{
                \"type\": \"password\",
                \"value\": \"${password}\",
                \"temporary\": false
            }]
        }"
    log_ok "User ${username} created with password '${password}'"
}

# ─── Create Identity Provider ──────────────────────────────────────────────────
# Sets up an OIDC Identity Provider in the TARGET realm that trusts the SOURCE realm.
# This is required for cross-realm token exchange.
create_identity_provider() {
    local realm=$1 alias=$2 issuer_uri=$3
    log_info "Creating IdP '${alias}' in realm '${realm}' → trusting ${issuer_uri}"

    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" \
        -H "Authorization: Bearer ${TOKEN}" \
        "${KEYCLOAK_URL}/admin/realms/${realm}/identity-provider/instances/${alias}")

    if [ "$code" = "200" ]; then
        log_warn "IdP ${alias} already exists in ${realm} — skipping"
        return 0
    fi

    curl -sf -X POST "${KEYCLOAK_URL}/admin/realms/${realm}/identity-provider/instances" \
        -H "Authorization: Bearer ${TOKEN}" \
        -H "Content-Type: application/json" \
        -d "{
            \"alias\": \"${alias}\",
            \"displayName\": \"${alias}\",
            \"providerId\": \"oidc\",
            \"enabled\": true,
            \"trustEmail\": true,
            \"storeToken\": false,
            \"addReadTokenRoleOnCreate\": false,
            \"firstBrokerLoginFlowAlias\": \"first broker login\",
            \"config\": {
                \"issuer\": \"${issuer_uri}\",
                \"authorizationUrl\": \"${issuer_uri}/protocol/openid-connect/auth\",
                \"tokenUrl\": \"${issuer_uri}/protocol/openid-connect/token\",
                \"logoutUrl\": \"${issuer_uri}/protocol/openid-connect/logout\",
                \"jwksUrl\": \"${issuer_uri}/protocol/openid-connect/certs\",
                \"userInfoUrl\": \"${issuer_uri}/protocol/openid-connect/userinfo\",
                \"defaultScope\": \"openid profile email\",
                \"useJwksUrl\": \"true\",
                \"backchannelSupported\": \"true\"
            }
        }" > /dev/null
    log_ok "IdP '${alias}' created in realm '${realm}'"
}

# ─── Enable token exchange permissions ─────────────────────────────────────────
# Allows 'source_client' to exchange tokens on behalf of 'target_client' in the given realm.
enable_token_exchange() {
    local realm=$1 source_client=$2 target_client=$3
    log_info "Enabling token exchange: ${source_client} → ${target_client} in ${realm}"

    local target_uuid
    target_uuid=$(client_uuid "$realm" "$target_client")
    if [ -z "$target_uuid" ]; then
        log_err "Target client ${target_client} not found in ${realm}"
        return 1
    fi

    # Step 1: Enable service accounts + authorization on the target client
    curl -sf -X PUT "${KEYCLOAK_URL}/admin/realms/${realm}/clients/${target_uuid}" \
        -H "Authorization: Bearer ${TOKEN}" \
        -H "Content-Type: application/json" \
        -d '{
            "authorizationServicesEnabled": true,
            "serviceAccountsEnabled": true
        }' > /dev/null

    # Step 2: Enable authz resource server on the target client
    curl -s -X POST "${KEYCLOAK_URL}/admin/realms/${realm}/clients/${target_uuid}/authz/resource-server" \
        -H "Authorization: Bearer ${TOKEN}" \
        -H "Content-Type: application/json" \
        -d '{}' > /dev/null 2>&1 || true

    # Step 3: Create an "allow-all" aggregate policy
    local policy_response
    policy_response=$(curl -s -X POST \
        "${KEYCLOAK_URL}/admin/realms/${realm}/clients/${target_uuid}/authz/resource-server/policy/aggregate" \
        -H "Authorization: Bearer ${TOKEN}" \
        -H "Content-Type: application/json" \
        -d '{
            "name": "allow-all-token-exchange",
            "description": "Allow all token exchange — example portfolio",
            "logic": "POSITIVE",
            "decisionStrategy": "AFFIRMATIVE",
            "policies": []
        }' 2>/dev/null)
    local policy_id
    policy_id=$(echo "$policy_response" | jq -r '.id // empty')

    # Step 4: Create scope-based permission
    if [ -n "$policy_id" ]; then
        curl -s -X POST \
            "${KEYCLOAK_URL}/admin/realms/${realm}/clients/${target_uuid}/authz/resource-server/permission/scope" \
            -H "Authorization: Bearer ${TOKEN}" \
            -H "Content-Type: application/json" \
            -d "{
                \"name\": \"token-exchange-permission\",
                \"description\": \"Permission for token exchange\",
                \"type\": \"scope\",
                \"logic\": \"POSITIVE\",
                \"decisionStrategy\": \"AFFIRMATIVE\",
                \"scopes\": [],
                \"policies\": [\"${policy_id}\"]
            }" > /dev/null 2>&1 || true
    fi

    log_ok "Token exchange enabled"
}

# ─── Main ──────────────────────────────────────────────────────────────────────
main() {
    log_header "Keycloak Token Exchange Example — Realm Setup"

    wait_for_keycloak
    get_admin_token

    # ── Create realms ──────────────────────────────────────────────────────────
    create_realm "${EXAMPLE_REALM}"
    create_realm "${GATEWAY_REALM}"

    # Refresh admin token after realm creation
    get_admin_token

    # ── Create clients ─────────────────────────────────────────────────────────
    # example-service-realm:
    #   - example-service-client: used by end-users / frontend to authenticate
    create_client "${EXAMPLE_REALM}" "example-service-client" "${EXAMPLE_CLIENT_SECRET}"

    # gateway-realm:
    #   - gateway-client: the API Gateway uses this client to perform token exchange
    create_client "${GATEWAY_REALM}" "gateway-client" "${GATEWAY_CLIENT_SECRET}"

    # ── Create Identity Provider in gateway-realm ──────────────────────────────
    # gateway-realm must trust tokens from example-service-realm
    # so it can exchange them.
    create_identity_provider "${GATEWAY_REALM}" "${EXAMPLE_REALM}" "${EXAMPLE_ISSUER}"

    # ── Enable token exchange in gateway-realm ─────────────────────────────────
    enable_token_exchange "${GATEWAY_REALM}" "gateway-client" "gateway-client"

    # ── Create test users ──────────────────────────────────────────────────────
    # Users authenticate against example-service-realm
    create_test_user "${EXAMPLE_REALM}" "testuser" "testpass" "testuser@example.com"
    create_test_user "${EXAMPLE_REALM}" "admin" "admin123" "admin@example.com"

    # ── Summary ────────────────────────────────────────────────────────────────
    log_header "Setup Complete!"

    echo -e "${GREEN}Realms:${NC}"
    echo "  • ${EXAMPLE_REALM}  — user logins"
    echo "  • ${GATEWAY_REALM}  — token exchange target"
    echo ""
    echo -e "${GREEN}Clients:${NC}"
    echo "  • ${EXAMPLE_REALM}/example-service-client  (secret: ${EXAMPLE_CLIENT_SECRET})"
    echo "  • ${GATEWAY_REALM}/gateway-client           (secret: ${GATEWAY_CLIENT_SECRET})"
    echo ""
    echo -e "${GREEN}Test credentials (${EXAMPLE_REALM}):${NC}"
    echo "  • testuser / testpass"
    echo "  • admin    / admin123"
    echo ""
    echo -e "${GREEN}Identity Providers:${NC}"
    echo "  • ${GATEWAY_REALM} trusts → ${EXAMPLE_REALM} (for token exchange)"
    echo ""
    echo -e "${GREEN}Keycloak Admin:${NC}"
    echo "  http://localhost:8180/admin  (admin / ${ADMIN_PASSWORD})"
}

main
