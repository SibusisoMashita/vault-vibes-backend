# Architecture

## AWS Infrastructure

```mermaid
flowchart TD
    subgraph Internet
        FE[Frontend\nReact / Vite]
        Dev[Developer\nGitHub Push]
    end

    subgraph AWS_us_east_1["AWS — us-east-1"]
        subgraph Compute
            ECS[ECS Fargate\nvault-vibes-cluster\nvault-backend-service]
            Lambda[Lambda\nvault-vibes-notification-handler]
        end

        subgraph Storage
            RDS[(RDS PostgreSQL\nvaultvibes DB)]
            S3[S3\nvault-vibes-uploads]
            ECR[ECR\nvaultvibes-api]
        end

        subgraph Identity
            Cognito[Cognito\nUser Pool\nus-east-1_Pmg4WjBdm]
            SecretsManager[Secrets Manager\nvaultvibes/prod/db\nvaultvibes/prod/app]
            IAM[IAM\nOIDC Deploy Role\nTask Execution Role\nTask Role]
        end

        subgraph Messaging
            EB[EventBridge\nvault-vibes-events\nsource: vaultvibes.finance]
            CW[CloudWatch Logs\n/ecs/vault-backend]
        end
    end

    subgraph External
        WA[WhatsApp\nCloud API\ngraph.facebook.com]
    end

    subgraph CICD["CI/CD — GitHub Actions"]
        GHA[deploy-prod.yml\ntest → build → push → deploy]
    end

    Dev --> GHA
    GHA -->|OIDC| IAM
    GHA -->|Push image| ECR
    GHA -->|Register task def + update service| ECS

    FE -->|JWT Bearer| ECS
    FE -->|Authenticate| Cognito
    Cognito -->|JWKS public keys| ECS

    ECS -->|JPA + Flyway| RDS
    ECS -->|PutObject / PreSigned URL| S3
    ECS -->|PutEvents| EB
    ECS -->|Logs| CW

    SecretsManager -->|Injected at task start| ECS

    EB -->|EventBridge rule| Lambda
    Lambda -->|HTTP POST| WA
```

## Application Layer

```mermaid
flowchart TD
    HTTP[HTTP Request] --> SC[SecurityFilterChain\nJWT validation]
    SC --> Controller[Controller]
    Controller --> PS[PermissionService\nrole check]
    Controller --> Service[Service]
    Service --> Repository[Spring Data JPA Repository]
    Repository --> DB[(PostgreSQL)]
    Service --> NES[NotificationEventService]
    NES --> EB[EventBridge]
    Service --> S3[S3UploadService /\nProofSignedUrlService]
    S3 --> AWS_S3[AWS S3]
```

## Design Decisions

### Ledger as source of truth
All pool money movement is recorded in `ledger_entries`. Pool stats (balance, liquidity, per-share value) are computed by aggregating ledger entries at query time. This makes the ledger auditable and prevents silent state drift.

### Stateless API
No HTTP sessions. Every request is authenticated via a short-lived Cognito JWT. The ECS service can scale horizontally without session affinity.

### Non-fatal notifications
`NotificationEventService` catches all EventBridge exceptions and logs them. A failed WhatsApp notification will never roll back a financial transaction.

### Role resolved from the database
After JWT validation extracts the Cognito `sub`, the API looks up the corresponding `users` row to get the current role. This means roles can be changed without re-issuing JWTs.

### Flyway for schema management
All DDL is version-controlled in `src/main/resources/db/migration/`. `ddl-auto: none` ensures Hibernate never modifies the schema.
