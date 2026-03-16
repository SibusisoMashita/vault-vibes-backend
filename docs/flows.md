# Workflow Flows

Visual guides for the main backend workflows.

## Invite Flow

How a new member joins the stokvel.

```mermaid
flowchart TD
    Admin[Admin / Treasurer] -->|POST /api/invitations| CreateUser[Create PENDING user in DB]
    CreateUser --> AllocateShares[Allocate shares]
    AllocateShares --> CreateCognito[Create Cognito user\nSMS suppressed]
    CreateCognito --> SendSMS[Send WinSMS\nwith temp password]
    SendSMS --> MarkSent[Mark invitation SENT]
    MarkSent --> MemberLogin[Member logs in\nwith temp password]
    MemberLogin --> ForceChange[Cognito forces\npassword change]
    ForceChange --> LinkAccount[Link cognito_id to DB user\nActivate account]
    LinkAccount --> Active[Member is ACTIVE]
```

## Authentication Flow

Every authenticated API request goes through this pipeline.

```mermaid
flowchart TD
    Request[HTTP Request] --> ExtractJWT[Extract Bearer token]
    ExtractJWT --> ValidateJWT[Validate JWT\nsignature + issuer + expiry + token_use=access]
    ValidateJWT -->|Invalid| Reject401[401 INVALID_TOKEN]
    ValidateJWT -->|Valid| ExtractSub[Extract sub claim]
    ExtractSub --> LookupUser[Lookup user by cognito_id]
    LookupUser -->|Not found| FallbackPhone[Fallback: resolve phone from Cognito\nlookup user by phone + link sub]
    FallbackPhone -->|Not found| Reject401b[401 USER_NOT_REGISTERED]
    LookupUser -->|Found| CheckStatus[Check user.status]
    FallbackPhone -->|Found| CheckStatus
    CheckStatus -->|SUSPENDED| Reject403[403 USER_NOT_ACTIVE]
    CheckStatus -->|ACTIVE| CacheUser[Cache in UserContextHolder]
    CacheUser --> CheckPermission[Check role permissions]
    CheckPermission -->|Denied| Reject403b[403 FORBIDDEN]
    CheckPermission -->|Allowed| ProcessRequest[Process request]
```

## Contribution Flow

Monthly payment submission and verification.

```mermaid
flowchart TD
    Member[Member] -->|POST /api/contributions\nmultipart with proof| UploadProof[Upload proof to S3]
    UploadProof --> RecordContribution[Record contribution\nstatus = PENDING]
    RecordContribution --> AdminReview[Admin reviews proof]
    AdminReview -->|POST /{id}/verify| Verify[Mark VERIFIED\nPost ledger entry]
    AdminReview -->|POST /{id}/reject| Reject[Mark REJECTED\nwith reason]

    Admin[Admin] -->|POST /api/contributions\nJSON, no proof| AutoVerify[Record contribution\nstatus = VERIFIED\nPost ledger entry]

    Verify --> CheckLoan{Active loan?}
    AutoVerify --> CheckLoan
    CheckLoan -->|Yes| RepayLoan[Settle loan repayment\nPost repayment ledger entry]
    CheckLoan -->|No| Done[Done]
    RepayLoan --> Done
```

## Loan Flow

Borrowing lifecycle from request to repayment.

```mermaid
flowchart TD
    Member[Member] -->|POST /api/loans/request| ValidateEligibility[Validate:\n- Account active\n- Owns shares\n- Within borrow limit\n- No cross-month loan]
    ValidateEligibility -->|Fail| RejectRequest[400 Bad Request]
    ValidateEligibility -->|Pass| CreateLoan[Create loan\nstatus = PENDING]
    CreateLoan --> AdminAction[Admin reviews]
    AdminAction -->|POST /{id}/approve| Approve[Status → ACTIVE\nNegative ledger entry\nNotify member]
    AdminAction -->|POST /{id}/reject| RejectLoan[Status → REJECTED]
    Approve --> Repayment[Repayment via\nnext contribution]
    Repayment --> FullyRepaid[Status → REPAID\nPositive ledger entry]
```

## Pool Valuation Flow

How pool stats are computed on every request.

```mermaid
flowchart TD
    Request[GET /api/pool/stats] --> SumLedger[SUM all ledger amounts\n= Bank Balance]
    SumLedger --> SumLoans[SUM outstanding loan balances]
    SumLoans --> CalcPool[Pool Value = Bank Balance + Outstanding Loans]
    CalcPool --> CalcShare[Share Value = Pool Value ÷ Shares Sold]
    CalcShare --> Response[Return PoolStatsDTO]
```

## Notification Flow

How business events reach members via WhatsApp.

```mermaid
flowchart LR
    Service[Service layer] -->|publish event| NES[NotificationEventService]
    NES -->|PutEvents| EB[EventBridge\nvault-vibes-events]
    EB -->|Rule trigger| Lambda[Lambda handler]
    Lambda -->|POST| WhatsApp[WhatsApp Cloud API]
    WhatsApp -->|Message| Phone[Member's phone]
```

Events: `LOAN_APPROVED`, `LOAN_ISSUED`, `CONTRIBUTION_OVERDUE`, `DISTRIBUTION_EXECUTED`, `MEMBER_INVITED`, `ROLE_UPDATED`.

Notification failures are logged but never roll back the originating business transaction.
