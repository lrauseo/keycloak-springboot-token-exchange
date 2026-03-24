# Keycloak Spring Boot Token Exchange — Exemplo de Portfólio

Um stack de microsserviços Spring Boot **limpo e mínimo** demonstrando o **token exchange** do Keycloak entre múltiplos realms.
Construído como projeto de portfólio para demonstrar padrões de integração OAuth2/OIDC.

## Arquitetura

```
┌─────────────────────────────────────────────────┐
│  Keycloak  (porta 8180)                         │
│  • example-service-realm  → login dos usuários  │
│  • gateway-realm          → troca de tokens     │
└─────────────────────────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────┐
│  API Gateway  (porta 8081)                      │
│  • Registrado no Eureka                         │
│  • Rate limiting via Redis                      │
│  • Filtro TokenExchange (customizado)           │
│    Troca o token do realm original → gateway    │
└─────────────────────────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────┐
│  Example Service  (porta 8080)                  │
│  • Registrado no Eureka                         │
│  • Validação JWT multi-realm                    │
│    Aceita tokens de ambos os realms             │
│  • Chama o example-service2 via Eureka          │
│  • Swagger UI em /swagger-ui.html               │
└─────────────────────────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────┐
│  Example Service 2  (porta 8082)                │
│  • Registrado no Eureka                         │
│  • Simulação de downstream interno              │
│  • Confia apenas em tokens do gateway-realm     │
│  • Recebe token encaminhado pelo service 1      │
└─────────────────────────────────────────────────┘
```

## Fluxo de Token Exchange

```
Usuário              Gateway                   Keycloak              Example Service
  │                     │                          │                        │
  │  GET /api/protected/hello                  │                        │
  │  Authorization: Bearer <token-example-realm>    │                        │
  │────────────────────►│                          │                        │
  │                     │  POST token exchange     │                        │
  │                     │  subject_token=<original>│                        │
  │                     │─────────────────────────►│                        │
  │                     │  access_token=<gateway-realm-token>               │
  │                     │◄─────────────────────────│                        │
  │                     │                          │                        │
  │                     │  GET /api/protected/hello (com novo token)        │
  │                     │─────────────────────────────────────────────────►│
  │                     │                          │  valida JWT             │
  │◄────────────────── 200 OK ──────────────────────────────────────────────│
```

## Fluxo de Integracao Encadeada (Gateway -> service1 -> service2)

```
Usuario                Gateway                Keycloak            Example Service         Example Service 2
  │                      │                       │                      │                          │
  │ GET /api/protected/chained                   │                      │                          │
  │ Authorization: Bearer <token-example-realm>  │                      │                          │
  │─────────────────────►│                       │                      │                          │
  │                      │ POST token exchange   │                      │                          │
  │                      │ subject_token=<orig>  │                      │                          │
  │                      │──────────────────────►│                      │                          │
  │                      │ <token-gateway-realm> │                      │                          │
  │                      │◄──────────────────────│                      │                          │
  │                      │ GET /api/protected/chained (token trocado)   │                          │
  │                      │──────────────────────────────────────────────►│                          │
  │                      │                       │                      │ GET /api/internal/hello  │
  │                      │                       │                      │ Authorization: Bearer <token-gateway-realm>
  │                      │                       │                      │─────────────────────────►│
  │                      │                       │                      │◄─────────────────────────│
  │                      │                       │                      │ agrega resposta por hops │
  │                      │◄──────────────────────────────────────────────│                          │
  │◄─────────────────────│ 200 OK + gatewayStep/service1Step/service2Step │                        │
```

Este e o cenario principal de portfolio para evidenciar o caminho completo da requisicao com token trocado e propagado.

## Início Rápido

### Pré-requisitos
- [Docker](https://www.docker.com/get-started) + Docker Compose v2
- Nenhuma instalação local de Java/Gradle necessária (Docker compila os projetos)

### Executar

```bash
cd keycloak-springboot-token-exchange

# Inicia o stack completo (compila as imagens automaticamente)
docker compose up --build

# Em background
docker compose up --build -d
```

A primeira inicialização leva ~3–5 minutos (Gradle baixa dependências e compila o JAR).

### Serviços

| Serviço | URL | Descrição |
|---------|-----|-----------|
| Keycloak | http://localhost:8180/admin | Console admin (admin / admin-secret) |
| Eureka | http://localhost:8761 | Registro de serviços |
| API Gateway | http://localhost:8081 | Ponto de entrada (com token exchange) |
| Example Service | http://localhost:8080 | Serviço de negócio (JWT multi-realm) |
| Example Service 2 | http://localhost:8082 | Serviço downstream interno (somente gateway-realm) |
| Swagger UI (service 1) | http://localhost:8080/swagger-ui.html | Documentação OpenAPI |

## Como Usar

### 1. Obter token do example-service-realm

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

### 2. Chamar o serviço diretamente (token do realm original)

```bash
# O serviço aceita este token diretamente (example-service-realm)
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/protected/token-info | jq .
# issuer será: example-service-realm
```

### 3. Chamar via API Gateway (token exchange automático)

```bash
# Mesmo token — o Gateway o troca para gateway-realm antes de encaminhar
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8081/api/protected/token-info | jq .
# issuer será: gateway-realm  ← token trocado!
```

### 4. Endpoint público (sem autenticação)

```bash
curl http://localhost:8080/api/public/hello
curl http://localhost:8081/api/public/hello  # via gateway
```

### 5. Integração encadeada completa (Gateway → service1 → service2)

```bash
# Chamada única mostrando toda a cadeia e propagação do token trocado
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8081/api/protected/chained | jq .

# Opcional: rota direta para o service2 passando pelo gateway
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8081/api/service2/internal/hello | jq .
```

## Estrutura do Projeto

```
keycloak-springboot-token-exchange/
│
├── api-gateway-example/          # Spring Cloud Gateway
│   └── src/main/java/br/com/lrs/gateway/
│       ├── config/
│       │   ├── SecurityConfig.java           # Segurança do Gateway
│       │   ├── RateLimitConfig.java          # Rate limiter Redis
│       │   ├── GatewayConfig.java            # Bean WebClient
│       │   └── TokenExchangeProperties.java  # @ConfigurationProperties
│       └── filter/
│           └── TokenExchangeGatewayFilterFactory.java  # ← CLASSE PRINCIPAL
│
├── example-service/              # Serviço REST Spring Boot
│   └── src/main/java/br/com/lrs/example/
│       ├── config/
│       │   ├── SecurityConfig.java    # Decoder JWT multi-realm  ← CLASSE PRINCIPAL
│       │   └── KeycloakProperties.java
│       └── controller/
│           └── ExampleController.java  # Endpoints REST
│
├── example-service2/             # Serviço REST Spring Boot downstream
│   └── src/main/java/br/com/lrs/example2/
│       ├── config/
│       │   ├── SecurityConfig.java     # Confia só em gateway-realm
│       │   └── KeycloakProperties.java
│       └── controller/
│           └── Service2Controller.java # Endpoints internos
│
├── keycloak-init/                # Container one-shot para setup de realms
│   ├── Dockerfile
│   └── init-realms.sh            # Cria realms, clients, IdP, usuários
│
├── http/
│   └── requests.http             # Exemplos para VS Code REST Client
│
├── docker-compose.yml            # Definição do stack completo
├── .env                          # Variáveis de ambiente
├── README.md                     # English version
└── README.pt-br.md               # Este arquivo
```

## Conceitos-Chave

### `TokenExchangeGatewayFilterFactory`
O núcleo desta demonstração. Um filtro Spring Cloud Gateway que:
1. Extrai o token `Bearer` da requisição
2. Chama o endpoint de troca de token do Keycloak usando o grant type OAuth2 `urn:ietf:params:oauth:grant-type:token-exchange`
3. Substitui o token na requisição encaminhada ao serviço

**Por quê?** O serviço downstream deve receber apenas tokens de um realm interno confiável (`gateway-realm`), não do realm de login do usuário final.

### `JwtDecoder` Multi-Issuer (example-service)
Em vez de confiar em um único realm do Keycloak, o serviço tenta validar o token contra cada endpoint JWK configurado:
- `example-service-realm/protocol/openid-connect/certs`
- `gateway-realm/protocol/openid-connect/certs`

Assim, o serviço funciona tanto quando chamado diretamente (token original) quanto via Gateway (token trocado).

### `JwtDecoder` somente gateway-realm (example-service2)
O `example-service2` é propositalmente mais restrito e confia apenas em `gateway-realm`.
Isso deixa o exemplo explícito: chamadas que tentam contornar o Gateway com token de `example-service-realm` falham, enquanto tokens trocados funcionam.

### Pré-requisitos para Token Exchange no Keycloak
- `KC_FEATURES: token-exchange` deve estar configurado no container Keycloak
- Um **Identity Provider** deve estar configurado em `gateway-realm` confiando em `example-service-realm`
- O `gateway-client` deve ter **authorization services** habilitado

Tudo isso é automatizado pelo script `keycloak-init/init-realms.sh`.

## Variáveis de Ambiente

| Variável | Padrão | Descrição |
|----------|--------|-----------|
| `KEYCLOAK_ADMIN` | `admin` | Usuário admin do Keycloak |
| `KEYCLOAK_ADMIN_PASSWORD` | `admin-secret` | Senha do admin |
| `EXAMPLE_SERVICE_REALM` | `example-service-realm` | Realm de login dos usuários |
| `GATEWAY_REALM` | `gateway-realm` | Realm destino da troca |
| `EXAMPLE_SERVICE_CLIENT_SECRET` | `example-service-secret` | Secret do client |
| `GATEWAY_CLIENT_SECRET` | `gateway-client-secret` | Secret do client do gateway |
| `KEYCLOAK_PORT` | `8180` | Porta do Keycloak |
| `GATEWAY_PORT` | `8081` | Porta do API Gateway |
| `EXAMPLE_SERVICE_PORT` | `8080` | Porta do Example Service |
| `EXAMPLE_SERVICE2_PORT` | `8082` | Porta do Example Service 2 |

## Solução de Problemas

### Serviços não inicializam
```bash
docker compose logs keycloak-init
docker compose logs api-gateway-example
docker compose logs example-service
docker compose logs example-service2
```

### Realm não criado
```bash
# Verificar se o Keycloak está pronto
curl http://localhost:8180/health/ready

# Reexecutar a inicialização
docker compose run --rm keycloak-init
```

### Token exchange retornando 400
1. Verifique se `KC_FEATURES: token-exchange` está configurado no Keycloak
2. Confira se o Identity Provider existe em `gateway-realm`:
   Admin → gateway-realm → Identity Providers
3. Verifique se os secrets nos `.env` correspondem

### Gateway não roteia para example-service
1. Verifique o dashboard do Eureka: http://localhost:8761 — `EXAMPLE-SERVICE` deve aparecer
2. Aguarde ~60s após inicialização para o registro propagar

### Gateway não roteia para example-service2
1. Verifique o dashboard do Eureka: http://localhost:8761 — `EXAMPLE-SERVICE2` deve aparecer
2. Valide o path da rota no gateway: `/api/service2/**`
3. Chame `http://localhost:8081/api/service2/public/info` para validar o roteamento primeiro

## Licença

MIT
