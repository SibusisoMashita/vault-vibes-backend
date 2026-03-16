# Financial Calculations

All financial formulas are centralized in `FinanceUtil.java`. No controller or service should implement these calculations directly.

## Pool Valuation

```mermaid
flowchart TD
    Contributions -->|Positive ledger entries| BankBalance[Bank Balance]
    LoanRepayments[Loan Repayments] -->|Positive ledger entries| BankBalance
    BankInterest[Bank Interest] -->|Positive ledger entries| BankBalance
    LoansIssued[Loans Issued] -->|Negative ledger entries| BankBalance
    BankBalance --> PoolValue[Pool Value]
    OutstandingLoans[Outstanding Loans] --> PoolValue
    PoolValue --> ShareValue[Share Value]
    ShareValue --> MemberValue[Member Value]
    MemberValue --> Profit
```

### Pool Value

The total value of the stokvel, including both liquid cash and money lent out.

```
Pool Value = Bank Balance + Outstanding Loans
```

- Bank Balance = SUM of all signed ledger entries (contributions add, loan disbursements subtract)
- Outstanding Loans = SUM of (principal − amount_repaid) for all active loans

### Share Value

What one share is currently worth. Fluctuates as the pool grows.

```
Share Value = Pool Value ÷ Total Funded Shares
```

If no shares have been sold yet, falls back to the configured share price.

### Member Value

What a member's stake is worth right now.

```
Member Value = Shares Owned × Share Value
```

### Profit

How much a member has gained (or lost) relative to what they've paid in.

```
Profit = Member Value − Contributions Paid
```

## Interest

Flat simple interest on loans. Not compounded.

```
Interest = Principal × (Rate ÷ 100)
```

Default rate: 20% (configurable via `/api/config/borrowing`).

## Contribution Amount

How much a member pays each month. Determined by their share count.

```
Contribution Amount = Share Units × Share Price
```

## Borrowing Limits

Two constraints determine how much a member can borrow:

```mermaid
flowchart TD
    MemberShares[Member's Shares] --> MemberValue[Member Value]
    MemberValue -->|× 50%| MemberLimit[Member Collateral Limit]
    ExistingLoans[Existing Loans] -->|subtract| MemberLimit
    BankBalance[Bank Balance] -->|× 50%| PoolLimit[Pool Liquidity Limit]
    OutstandingLoans[Outstanding Loans] -->|subtract| PoolLimit
    MemberLimit --> FinalLimit[Available to Borrow = MIN of both]
    PoolLimit --> FinalLimit
```

1. Member collateral rule: can borrow up to 50% of their share value, minus any outstanding loans
2. Pool liquidity rule: the stokvel must retain at least 50% of its cash balance

```
Member Limit = (Member Value × 0.50) − User's Outstanding Loans
Pool Limit   = (Bank Balance × 0.50) − Total Outstanding Loans
Available    = MIN(Member Limit, Pool Limit)
```

## Year-End Projection

Estimates the pool value at distribution time by extrapolating current trends.

```
Projected Pool = Current Pool
               + (Monthly Contributions × Months Remaining)
               + Expected Loan Interest
               + (Avg Monthly Bank Interest × Months Remaining)
```

Bank interest is estimated from a 3-month rolling average of `BANK_INTEREST` ledger entries.
