# Keycloak Spring Boot Token Exchange — Portfolio Example

A **clean, minimal** Spring Boot microservices stack demonstrating Keycloak **token exchange** across multiple realms.
Built as a portfolio project to showcase OAuth2/OIDC integration patterns.

## Architecture

```
┌─────────────────────────────────────────────────┐
│  Keycloak  (port 8180)                          │
│  • example-service-realm  → user logins         │
│  • gateway-realm          → token exchange      │
└─────────────────────────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────┐
│  API Gateway  (port 8081)                       │
│  • Registered on Eureka                         │
│  • Rate limiting via Redis                      │
│  • TokenExchange filter (custom)                │
│    Exchanges incoming realm token → gateway     │
└─────────────────────────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────┐
│  Example Service  (port 8080)                   │
│  • Registered on Eureka                         │
│  • Multi-realm JWT validation                   │
│    Accepts tokens from both realms              │
│  • Calls example-service2 via Eureka            │
│  • Swagger UI at /swagger-ui.html               │
└─────────────────────────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────┐
│  Example Service 2  (port 8082)                 │
│  • Registered on Eureka                         │
│  • Internal downstream simulation               │
│  • Trusts only gateway-realm tokens             │
│  • Receives forwarded token from service 1      │
└─────────────────────────────────────────────────┘
```

## Token Exchange Flow

```
User                    Gateway                     Keycloak                 Example Service
 │                         │                            │                          │
 │  GET /api/protected/hello               │                          │
 │  Authorization: Bearer <example-realm-token>         │                          │
 │────────────────────────►│                            │                          │
 │                         │  POST /realms/gateway-realm/protocol/openid-connect/token
 │                         │  grant_type=token-exchange │                          │
 │                         │  subject_token=<original>  │                          │
 │                         │───────────────────────────►│                          │
 │                         │  access_token=<gateway-realm-token>                   │
 │                         │◄───────────────────────────│                          │
 │                         │                            │                          │
 │                         │  GET /api/protected/hello                             │
 │                         │  Authorization: Bearer <gateway-realm-token>          │
 │                         │─────────────────────────────────────────────────────►│
 │                         │                            │  validate JWT             │
 │                         │                            │◄─────────────────────────│
 │                         │               200 OK + response body                  │
 │◄────────────────────────────────────────────────────────────────────────────────│
```

## Chained Integration Flow (Gateway -> service1 -> service2)

```
User                  Gateway                Keycloak           Example Service         Example Service 2
 │                       │                      │                     │                         │
 │ GET /api/protected/chained                  │                     │                         │
 │ Authorization: Bearer <example-realm-token> │                     │                         │
 │──────────────────────►│                      │                     │                         │
 │                       │ POST token exchange  │                     │                         │
 │                       │ subject_token=<orig> │                     │                         │
 │                       │─────────────────────►│                     │                         │
 │                       │ <gateway-realm-token>│                     │                         │
 │                       │◄─────────────────────│                     │                         │
 │                       │ GET /api/protected/chained (gateway token) │                         │
 │                       │───────────────────────────────────────────►│                         │
 │                       │                      │                     │ GET /api/internal/hello │
 │                       │                      │                     │ Authorization: Bearer <gateway-realm-token>
 │                       │                      │                     │────────────────────────►│
 │                       │                      │                     │◄────────────────────────│
 │                       │                      │                     │ aggregate hop response  │
 │                       │◄───────────────────────────────────────────│                         │
 │◄──────────────────────│ 200 OK + gatewayStep/service1Step/service2Step │                     │
```

This is the portfolio scenario that demonstrates the full request round-trip with propagated exchanged token.

## Quick Start

### Prerequisites
- [Docker](https://www.docker.com/get-started) + Docker Compose v2
- No local Java/Gradle installation needed (Docker builds the apps)

### Run

```bash
cd keycloak-springboot-token-exchange

# Start the full stack (builds images automatically)
docker compose up --build

# Or in background
docker compose up --build -d
```

First startup takes ~3–5 minutes (Gradle downloads deps + builds JAR).

### Services

| Service | URL | Description |
|---------|-----|-------------|
| Keycloak | http://localhost:8180/admin | Admin console (admin / admin-secret) |
| Eureka | http://localhost:8761 | Service registry |
| API Gateway | http://localhost:8081 | Entry point (with token exchange) |
| Example Service | http://localhost:8080 | Business service (multi-realm JWT) |
| Example Service 2 | http://localhost:8082 | Internal downstream service (gateway-realm only) |
| Swagger UI (service 1) | http://localhost:8080/swagger-ui.html | OpenAPI docs |

## Usage

### 1. Get a token from example-service-realm

```bash
TOKEN=$(curl -s -X POST http://localhost:8180/realms/example-service-realm/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=testuser" \
  -d "password=testpass" \
  -d "grant_type=password" \
  -d "client_id=example-service-client" \
  -d "client_secret=example-service-secret" \
  | jq -r '.access_token')

echo "Token: ${TOKEN:0:40}..."
```

### 2. Call Example Service directly (original realm token)

```bash
# The example-service accepts this token directly (example-service-realm)
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/protected/token-info | jq .
# issuer will be: example-service-realm
```

### 3. Call via API Gateway (token exchange happens automatically)

```bash
# Same token — Gateway will exchange it to gateway-realm before forwarding
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8081/api/protected/token-info | jq .
# issuer will be: gateway-realm  ← this is the exchanged token!
```

### 4. Public endpoint (no auth)

```bash
curl http://localhost:8080/api/public/hello
curl http://localhost:8081/api/public/hello  # via gateway
```

### 5. Full chained integration (Gateway → service1 → service2)

```bash
# Single call showing full hop chain and propagated exchanged token
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8081/api/protected/chained | jq .

# Optional: direct route to service2 through gateway
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8081/api/service2/internal/hello | jq .
```

## Project Structure

```
keycloak-springboot-token-exchange/
│
├── api-gateway-example/          # Spring Cloud Gateway
│   └── src/main/java/br/com/lrs/gateway/
│       ├── config/
│       │   ├── SecurityConfig.java           # Gateway security (pass-through)
│       │   ├── RateLimitConfig.java          # Redis rate limiter
│       │   ├── GatewayConfig.java            # WebClient bean
│       │   └── TokenExchangeProperties.java  # @ConfigurationProperties
│       └── filter/
│           └── TokenExchangeGatewayFilterFactory.java  # ← KEY CLASS
│
├── example-service/              # Spring Boot REST service
│   └── src/main/java/br/com/lrs/example/
│       ├── config/
│       │   ├── SecurityConfig.java    # Multi-realm JWT decoder  ← KEY CLASS
│       │   └── KeycloakProperties.java
│       └── controller/
│           └── ExampleController.java  # REST endpoints
│
├── example-service2/             # Downstream Spring Boot REST service
│   └── src/main/java/br/com/lrs/example2/
│       ├── config/
│       │   ├── SecurityConfig.java     # Trusts only gateway-realm tokens
│       │   └── KeycloakProperties.java
│       └── controller/
│           └── Service2Controller.java # Internal endpoints
│
├── keycloak-init/                # One-shot container for realm setup
│   ├── Dockerfile
│   └── init-realms.sh            # Creates realms, clients, IdP, users
│
├── http/
│   └── requests.http             # VS Code REST Client examples
│
├── docker-compose.yml            # Full stack definition
├── .env                          # Environment variables
├── README.md                     # This file
└── README.pt-br.md               # Portuguese version
```

## Key Concepts

### `TokenExchangeGatewayFilterFactory`
The core of this demo. A Spring Cloud Gateway filter that:
1. Extracts the `Bearer` token from the request
2. Posts to Keycloak's token exchange endpoint using OAuth2 grant type `urn:ietf:params:oauth:grant-type:token-exchange`
3. Replaces the token in the forwarded request

**Why?** The downstream service should only see tokens from a trusted internal realm (`gateway-realm`), not from the end-user's login realm.

### Multi-Issuer `JwtDecoder` (example-service)
Instead of trusting a single Keycloak realm, the service tries to validate the token against every configured JWK endpoint:
- `example-service-realm/protocol/openid-connect/certs`
- `gateway-realm/protocol/openid-connect/certs`

This way, the service works both when called directly (original token) and via the Gateway (exchanged token).

### gateway-realm-only `JwtDecoder` (example-service2)
`example-service2` is intentionally stricter and trusts only `gateway-realm`.
This makes the demo explicit: calls that bypass the Gateway with a token from `example-service-realm` should fail, while exchanged tokens succeed.

### Keycloak Token Exchange Prerequisites
- `KC_FEATURES: token-exchange` must be set on the Keycloak container
- An **Identity Provider** must be configured in `gateway-realm` trusting `example-service-realm`
- The `gateway-client` must have **authorization services** enabled

All of this is automated by `keycloak-init/init-realms.sh`.

## Development

### Local run (with Docker Compose for infrastructure only)

```bash
# Start only infrastructure
docker compose up redis keycloak keycloak-db eureka keycloak-init -d

# Run services locally
cd example-service
./gradlew bootRun   # needs Gradle 8.11+ installed

cd api-gateway-example
./gradlew bootRun
```

> **Note:** The projects use Gradle but don't include the wrapper JAR.
> For local development, install [Gradle 8.11+](https://gradle.org/install/) or run `gradle wrapper` once.

### Build Docker images manually

```bash
docker build -t api-gateway-example ./api-gateway-example
docker build -t example-service ./example-service
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `KEYCLOAK_ADMIN` | `admin` | Keycloak admin username |
| `KEYCLOAK_ADMIN_PASSWORD` | `admin-secret` | Keycloak admin password |
| `EXAMPLE_SERVICE_REALM` | `example-service-realm` | User login realm |
| `GATEWAY_REALM` | `gateway-realm` | Token exchange target realm |
| `EXAMPLE_SERVICE_CLIENT_SECRET` | `example-service-secret` | Client secret |
| `GATEWAY_CLIENT_SECRET` | `gateway-client-secret` | Gateway client secret |
| `KEYCLOAK_PORT` | `8180` | Keycloak port |
| `GATEWAY_PORT` | `8081` | API Gateway port |
| `EXAMPLE_SERVICE_PORT` | `8080` | Example Service port |
| `EXAMPLE_SERVICE2_PORT` | `8082` | Example Service 2 port |

## Troubleshooting

### Services not starting
```bash
docker compose logs keycloak-init
docker compose logs api-gateway-example
docker compose logs example-service
docker compose logs example-service2
```

### Realm not created
```bash
# Check if Keycloak is ready
curl http://localhost:8180/health/ready

# Re-run realm init
docker compose run --rm keycloak-init
```

### Token exchange returning 400
1. Verify `KC_FEATURES: token-exchange` is set on Keycloak
2. Check that the Identity Provider exists in `gateway-realm`:
   Admin → gateway-realm → Identity Providers
3. Verify the client secret matches in `.env`

### Gateway not routing to example-service
1. Check Eureka dashboard: http://localhost:8761 — `EXAMPLE-SERVICE` must appear
2. Give it ~60s after startup for registration to propagate

### Gateway not routing to example-service2
1. Check Eureka dashboard: http://localhost:8761 — `EXAMPLE-SERVICE2` must appear
2. Validate gateway route path: `/api/service2/**`
3. Call `http://localhost:8081/api/service2/public/info` to validate routing first

## License

MIT
