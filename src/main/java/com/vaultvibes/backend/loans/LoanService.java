package com.vaultvibes.backend.loans;

import com.vaultvibes.backend.config.StokvelConfigService;
import com.vaultvibes.backend.ledger.LedgerEntryEntity;
import com.vaultvibes.backend.ledger.LedgerEntryRepository;
import com.vaultvibes.backend.loans.dto.LoanDTO;
import com.vaultvibes.backend.loans.dto.LoanRequestDTO;
import com.vaultvibes.backend.notifications.NotificationEventDetail;
import com.vaultvibes.backend.notifications.NotificationEventService;
import com.vaultvibes.backend.notifications.NotificationEventType;
import com.vaultvibes.backend.shares.ShareRepository;
import com.vaultvibes.backend.users.UserEntity;
import com.vaultvibes.backend.users.UserRepository;
import com.vaultvibes.backend.util.FinanceUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class LoanService {

    private static final BigDecimal POOL_LIQUIDITY_RATIO    = new BigDecimal("0.50");
    private static final BigDecimal MEMBER_COLLATERAL_RATIO = new BigDecimal("0.50");

    private final LoanRepository loanRepository;
    private final UserRepository userRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final ShareRepository shareRepository;
    private final StokvelConfigService configService;
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
        log.info("LOAN_REQUESTED: amount={} user={}", request.amount(), request.userId());

        UserEntity user = userRepository.findById(request.userId())
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + request.userId()));

        if ("PENDING".equals(user.getStatus())) {
            throw new IllegalArgumentException("Your account is not yet active. Contact the treasurer.");
        }

        BigDecimal configuredRate = configService.getInterestRate();

        // Member must own at least one share
        BigDecimal memberShares = shareRepository.sumShareUnitsByUserId(request.userId());
        if (memberShares.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("You must hold at least one share to request a loan.");
        }

        // Calculate pool and share values using centralized formulas
        BigDecimal sharesSold       = shareRepository.sumAllShareUnits();
        BigDecimal bankBalance      = ledgerEntryRepository.sumAllLedgerAmounts();
        BigDecimal outstandingLoans = loanRepository.sumOutstandingLoansBalance();
        BigDecimal totalPoolValue   = FinanceUtil.calculatePoolValue(bankBalance, outstandingLoans);
        BigDecimal perShareValue    = FinanceUtil.calculateShareValue(
                totalPoolValue, sharesSold, configService.getSharePrice());
        BigDecimal memberShareValue = FinanceUtil.calculateMemberValue(memberShares, perShareValue);

        // Borrowing limits
        BigDecimal userOutstanding   = loanRepository.sumOutstandingLoansByUserId(request.userId());
        BigDecimal memberBorrowLimit = FinanceUtil.calculateMemberBorrowLimit(
                memberShareValue, userOutstanding, MEMBER_COLLATERAL_RATIO);
        BigDecimal poolBorrowLimit   = FinanceUtil.calculatePoolBorrowLimit(
                bankBalance, outstandingLoans, POOL_LIQUIDITY_RATIO);
        BigDecimal availableToBorrow = memberBorrowLimit.min(poolBorrowLimit);

        // Cross-month restriction
        if (loanRepository.countCrossMonthActiveLoans(request.userId()) > 0) {
            throw new IllegalArgumentException(
                    "You have an active loan from a previous month. Repay it before requesting a new one.");
        }

        if (request.amount().compareTo(availableToBorrow) > 0) {
            throw new IllegalArgumentException(
                    "Requested amount exceeds your borrowing limit. Maximum: " + availableToBorrow);
        }

        LoanEntity loan = new LoanEntity();
        loan.setUser(user);
        loan.setPrincipalAmount(request.amount());
        loan.setInterestRate(configuredRate);
        loan.setStatus("PENDING");

        LoanEntity saved = loanRepository.saveAndFlush(loan);
        log.info("LOAN_CREATED: id={} user={}", saved.getId(), user.getFullName());
        return toDTO(saved);
    }

    @Transactional
    public LoanDTO approveLoan(UUID loanId) {
        log.info("LOAN_APPROVED: id={}", loanId);
        LoanEntity loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new IllegalArgumentException("Loan not found: " + loanId));
        loan.setStatus("ACTIVE");
        OffsetDateTime now = OffsetDateTime.now();
        loan.setIssuedAt(now);
        loan.setDueAt(now.with(TemporalAdjusters.lastDayOfMonth())
                .withHour(23).withMinute(59).withSecond(59).withNano(0));
        LoanEntity saved = loanRepository.save(loan);

        // Negative ledger entry: cash leaves the pool
        LedgerEntryEntity entry = new LedgerEntryEntity();
        entry.setUser(loan.getUser());
        entry.setEntryType("LOAN_ISSUED");
        entry.setEntryScope("USER");
        entry.setAmount(loan.getPrincipalAmount().negate());
        entry.setReference(loanId.toString());
        entry.setDescription("Loan issued — " + loan.getUser().getFullName());
        entry.setPostedAt(now);
        ledgerEntryRepository.save(entry);

        notificationEventService.publish(
                NotificationEventType.LOAN_APPROVED,
                new NotificationEventDetail(saved.getUser().getId(), saved.getUser().getPhoneNumber(), saved.getPrincipalAmount()));
        notificationEventService.publish(
                NotificationEventType.LOAN_ISSUED,
                new NotificationEventDetail(saved.getUser().getId(), saved.getUser().getPhoneNumber(), saved.getPrincipalAmount()));

        return toDTO(saved);
    }

    @Transactional
    public LoanDTO rejectLoan(UUID loanId) {
        log.info("LOAN_REJECTED: id={}", loanId);
        LoanEntity loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new IllegalArgumentException("Loan not found: " + loanId));
        loan.setStatus("REJECTED");
        return toDTO(loanRepository.save(loan));
    }

    @Transactional
    public LoanDTO markRepaid(UUID loanId) {
        log.info("LOAN_REPAID: id={}", loanId);
        LoanEntity loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new IllegalArgumentException("Loan not found: " + loanId));

        BigDecimal interest       = FinanceUtil.calculateFlatInterest(loan.getPrincipalAmount(), loan.getInterestRate());
        BigDecimal totalRepayment = loan.getPrincipalAmount().add(interest);
        loan.setAmountRepaid(totalRepayment);
        loan.setStatus("REPAID");
        LoanEntity saved = loanRepository.save(loan);

        // Positive ledger entry: principal + interest returns to the pool
        LedgerEntryEntity entry = new LedgerEntryEntity();
        entry.setUser(loan.getUser());
        entry.setEntryType("LOAN_REPAYMENT");
        entry.setEntryScope("USER");
        entry.setAmount(totalRepayment);
        entry.setReference(loanId.toString());
        entry.setDescription("Loan repayment — " + loan.getUser().getFullName());
        entry.setPostedAt(OffsetDateTime.now());
        ledgerEntryRepository.save(entry);

        return toDTO(saved);
    }

    public LoanDTO toDTO(LoanEntity loan) {
        BigDecimal interest       = FinanceUtil.calculateFlatInterest(loan.getPrincipalAmount(), loan.getInterestRate());
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
}
