package com.vaultvibes.backend.loans;

import com.vaultvibes.backend.config.BorrowingConfigRepository;
import com.vaultvibes.backend.ledger.LedgerEntryEntity;
import com.vaultvibes.backend.ledger.LedgerEntryRepository;
import com.vaultvibes.backend.loans.dto.LoanDTO;
import com.vaultvibes.backend.loans.dto.LoanRequestDTO;
import com.vaultvibes.backend.config.StokvelConfigRepository;
import com.vaultvibes.backend.notifications.NotificationEventDetail;
import com.vaultvibes.backend.notifications.NotificationEventService;
import com.vaultvibes.backend.notifications.NotificationEventType;
import com.vaultvibes.backend.shares.ShareRepository;
import com.vaultvibes.backend.users.UserEntity;
import com.vaultvibes.backend.users.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class LoanService {

    private static final BigDecimal DEFAULT_INTEREST_RATE    = new BigDecimal("20.00");
    private static final BigDecimal POOL_LIQUIDITY_RATIO    = new BigDecimal("0.50");
    private static final BigDecimal MEMBER_COLLATERAL_RATIO = new BigDecimal("0.50");
    private static final BigDecimal DEFAULT_SHARE_PRICE     = new BigDecimal("5000.00");

    private final LoanRepository loanRepository;
    private final UserRepository userRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final BorrowingConfigRepository borrowingConfigRepository;
    private final ShareRepository shareRepository;
    private final StokvelConfigRepository stokvelConfigRepository;
    private final NotificationEventService notificationEventService;

    public List<LoanDTO> listAll() {
        return loanRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toDTO)
                .toList();
    }

    public List<LoanDTO> listForUser(UUID userId) {
        return loanRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toDTO)
                .toList();
    }

    @Transactional
    public LoanDTO requestLoan(LoanRequestDTO request) {
        log.info("Borrowing request of {} from user {}", request.amount(), request.userId());

        UserEntity user = userRepository.findById(request.userId())
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + request.userId()));

        // Rule 1: Member eligibility — account must be active
        if ("PENDING".equals(user.getStatus())) {
            throw new IllegalArgumentException("Your account is not yet active. Contact the treasurer.");
        }

        BigDecimal configuredRate = borrowingConfigRepository.findAll()
                .stream().findFirst()
                .map(c -> c.getInterestRate())
                .orElse(DEFAULT_INTEREST_RATE);

        // --- Borrowing limit enforcement ---
        // 1. Member collateral rule: 50% of member's share value minus their own outstanding loans
        BigDecimal memberShares = shareRepository.sumShareUnitsByUserId(request.userId());

        // Rule 1: Member eligibility — must own at least one share
        if (memberShares.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("You must hold at least one share to request a loan.");
        }

        BigDecimal sharesSold   = shareRepository.sumAllShareUnits();
        BigDecimal bankBalance  = ledgerEntryRepository.sumAllLedgerAmounts();
        BigDecimal outstandingLoans = loanRepository.sumOutstandingLoansBalance();
        BigDecimal totalPoolValue   = bankBalance.add(outstandingLoans);

        BigDecimal perShareValue = sharesSold.compareTo(BigDecimal.ZERO) > 0
                ? totalPoolValue.divide(sharesSold, 2, RoundingMode.HALF_UP)
                : stokvelConfigRepository.findAll().stream().findFirst()
                    .map(c -> c.getSharePrice()).orElse(DEFAULT_SHARE_PRICE);

        BigDecimal memberShareValue = memberShares.multiply(perShareValue).setScale(2, RoundingMode.HALF_UP);

        // Rule 4: Deduct the member's own outstanding loans from their personal borrow limit
        BigDecimal userOutstanding   = loanRepository.sumOutstandingLoansByUserId(request.userId());
        BigDecimal memberBorrowLimit = memberShareValue
                .multiply(MEMBER_COLLATERAL_RATIO)
                .subtract(userOutstanding)
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);

        // 2. Pool liquidity rule: stokvel must keep 50% of cash liquidity
        BigDecimal poolLimit       = bankBalance.multiply(POOL_LIQUIDITY_RATIO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal poolBorrowLimit = poolLimit.subtract(outstandingLoans).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        // 3. Final limit: MIN(member collateral after deduction, pool available)
        BigDecimal availableToBorrow = memberBorrowLimit.min(poolBorrowLimit);

        // Rule 6: Cross-month restriction — block if the user has an ACTIVE loan from a prior month
        long crossMonthLoans = loanRepository.countCrossMonthActiveLoans(request.userId());
        if (crossMonthLoans > 0) {
            throw new IllegalArgumentException(
                    "You have an active loan from a previous month. Repay it before requesting a new one.");
        }

        if (request.amount().compareTo(availableToBorrow) > 0) {
            throw new IllegalArgumentException(
                    "Requested amount exceeds your borrowing limit. Maximum: "
                    + availableToBorrow.setScale(2, RoundingMode.HALF_UP));
        }

        LoanEntity loan = new LoanEntity();
        loan.setUser(user);
        loan.setPrincipalAmount(request.amount());
        loan.setInterestRate(configuredRate);
        loan.setStatus("PENDING");

        // saveAndFlush triggers the INSERT immediately so @CreationTimestamp is populated
        // before toDTO() reads it — plain save() defers the flush to transaction commit.
        LoanEntity saved = loanRepository.saveAndFlush(loan);
        log.info("Borrowing {} created for user {}", saved.getId(), user.getFullName());
        return toDTO(saved);
    }

    @Transactional
    public LoanDTO approveLoan(UUID loanId) {
        log.info("Approving borrowing {}", loanId);
        LoanEntity loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new IllegalArgumentException("Borrowing not found: " + loanId));
        loan.setStatus("ACTIVE");
        OffsetDateTime now = OffsetDateTime.now();
        loan.setIssuedAt(now);
        // Due at end of current month — borrowing must be repaid by month end
        loan.setDueAt(now.with(TemporalAdjusters.lastDayOfMonth())
                .withHour(23).withMinute(59).withSecond(59).withNano(0));
        LoanEntity saved = loanRepository.save(loan);

        // Write a negative ledger entry: cash leaves the pool when the loan is disbursed
        LedgerEntryEntity entry = new LedgerEntryEntity();
        entry.setUser(loan.getUser());
        entry.setEntryType("LOAN_ISSUED");
        entry.setAmount(loan.getPrincipalAmount().negate());   // negative: money leaves the pool
        entry.setReference(loanId.toString());
        entry.setDescription("Loan issued — " + loan.getUser().getFullName());
        entry.setPostedAt(now);
        ledgerEntryRepository.save(entry);

        // Notify member: loan approved and funds issued
        notificationEventService.publish(
                NotificationEventType.LOAN_APPROVED,
                new NotificationEventDetail(
                        saved.getUser().getId(),
                        saved.getUser().getPhoneNumber(),
                        saved.getPrincipalAmount()));
        notificationEventService.publish(
                NotificationEventType.LOAN_ISSUED,
                new NotificationEventDetail(
                        saved.getUser().getId(),
                        saved.getUser().getPhoneNumber(),
                        saved.getPrincipalAmount()));

        return toDTO(saved);
    }

    @Transactional
    public LoanDTO rejectLoan(UUID loanId) {
        log.info("Rejecting borrowing {}", loanId);
        LoanEntity loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new IllegalArgumentException("Borrowing not found: " + loanId));
        loan.setStatus("REJECTED");
        return toDTO(loanRepository.save(loan));
    }

    @Transactional
    public LoanDTO markRepaid(UUID loanId) {
        log.info("Marking borrowing {} as repaid", loanId);
        LoanEntity loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new IllegalArgumentException("Borrowing not found: " + loanId));

        BigDecimal interest       = flatInterest(loan.getPrincipalAmount(), loan.getInterestRate());
        BigDecimal totalRepayment = loan.getPrincipalAmount().add(interest);
        loan.setAmountRepaid(totalRepayment);
        loan.setStatus("REPAID");
        LoanEntity saved = loanRepository.save(loan);

        // Write a positive ledger entry: principal + interest returns to the pool
        LedgerEntryEntity entry = new LedgerEntryEntity();
        entry.setUser(loan.getUser());
        entry.setEntryType("LOAN_REPAYMENT");
        entry.setAmount(totalRepayment);   // positive: principal + interest enters the pool
        entry.setReference(loanId.toString());
        entry.setDescription("Loan repayment — " + loan.getUser().getFullName());
        entry.setPostedAt(OffsetDateTime.now());
        ledgerEntryRepository.save(entry);

        return toDTO(saved);
    }

    public LoanDTO toDTO(LoanEntity loan) {
        BigDecimal interest       = flatInterest(loan.getPrincipalAmount(), loan.getInterestRate());
        BigDecimal totalRepayment = loan.getPrincipalAmount().add(interest);
        BigDecimal remaining      = totalRepayment.subtract(loan.getAmountRepaid()).max(BigDecimal.ZERO);

        return new LoanDTO(
                loan.getId(),
                loan.getUser().getId(),
                loan.getUser().getFullName(),
                loan.getPrincipalAmount(),
                loan.getInterestRate(),
                interest,
                totalRepayment,
                loan.getAmountRepaid(),
                remaining,
                loan.getStatus().toLowerCase(),
                loan.getIssuedAt(),
                loan.getDueAt(),
                loan.getCreatedAt()
        );
    }

    /**
     * Flat simple interest: principal × (rate / 100), rounded to 2 decimal places.
     */
    private BigDecimal flatInterest(BigDecimal principal, BigDecimal annualRate) {
        return principal
                .multiply(annualRate)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }
}
