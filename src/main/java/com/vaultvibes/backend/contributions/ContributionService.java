package com.vaultvibes.backend.contributions;

import com.vaultvibes.backend.config.StokvelConfigService;
import com.vaultvibes.backend.contributions.dto.ContributionDTO;
import com.vaultvibes.backend.contributions.dto.ContributionPreviewDTO;
import com.vaultvibes.backend.contributions.dto.ContributionProofInfoDTO;
import com.vaultvibes.backend.contributions.dto.ContributionRequestDTO;
import com.vaultvibes.backend.ledger.LedgerEntryEntity;
import com.vaultvibes.backend.ledger.LedgerEntryRepository;
import com.vaultvibes.backend.loans.LoanEntity;
import com.vaultvibes.backend.loans.LoanRepository;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ContributionService {

    private final ContributionRepository contributionRepository;
    private final UserRepository userRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final ShareRepository shareRepository;
    private final LoanRepository loanRepository;
    private final StokvelConfigService configService;
    private final NotificationEventService notificationEventService;

    public List<ContributionDTO> listAll() {
        return contributionRepository.findAllByOrderByContributionDateDesc().stream()
                .map(this::toDTO)
                .toList();
    }

    public List<ContributionDTO> listForUser(UUID userId) {
        return contributionRepository.findByUserIdOrderByContributionDateDesc(userId).stream()
                .map(this::toDTO)
                .toList();
    }

    /**
     * Returns a payment breakdown for the given user without persisting anything.
     */
    public ContributionPreviewDTO getPreview(UUID userId) {
        BigDecimal shareUnits         = shareRepository.sumShareUnitsByUserId(userId);
        BigDecimal sharePrice         = configService.getSharePrice();
        BigDecimal contributionAmount = FinanceUtil.calculateContributionAmount(shareUnits, sharePrice);

        LocalDate today = LocalDate.now();
        int todayYearMonth = today.getYear() * 100 + today.getMonthValue();
        boolean hasContributedThisMonth = contributionRepository
                .countByUserIdAndYearMonth(userId, todayYearMonth) > 0;

        Optional<LoanEntity> activeLoan = findActiveLoan(userId);

        if (activeLoan.isEmpty()) {
            return new ContributionPreviewDTO(
                    shareUnits, sharePrice, contributionAmount,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    contributionAmount, null, hasContributedThisMonth);
        }

        LoanEntity loan           = activeLoan.get();
        BigDecimal interest       = FinanceUtil.calculateFlatInterest(loan.getPrincipalAmount(), loan.getInterestRate());
        BigDecimal totalRepayment = loan.getPrincipalAmount().add(interest);
        BigDecimal loanOutstanding = totalRepayment.subtract(loan.getAmountRepaid()).max(BigDecimal.ZERO);
        BigDecimal totalDue        = contributionAmount.add(loanOutstanding);

        return new ContributionPreviewDTO(
                shareUnits, sharePrice, contributionAmount,
                loanOutstanding, interest, loanOutstanding,
                totalDue, loan.getId(), hasContributedThisMonth);
    }

    /**
     * Records a monthly contribution and, if the member has an active loan,
     * simultaneously processes the outstanding loan repayment.
     *
     * @param request DTO — proofS3Key carries the S3 object key (not a URL)
     */
    @Transactional
    public ContributionDTO addContribution(ContributionRequestDTO request) {
        UserEntity user = userRepository.findById(request.userId())
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + request.userId()));

        // One-per-month guard
        LocalDate date = request.contributionDate();
        int yearMonth = date.getYear() * 100 + date.getMonthValue();
        if (contributionRepository.countByUserIdAndYearMonth(request.userId(), yearMonth) > 0) {
            throw new IllegalStateException(
                    "A contribution for " + date.getYear() + "-"
                    + String.format("%02d", date.getMonthValue())
                    + " has already been recorded for this member.");
        }

        BigDecimal shareUnits         = shareRepository.sumShareUnitsByUserId(request.userId());
        BigDecimal sharePrice         = configService.getSharePrice();
        BigDecimal contributionAmount = FinanceUtil.calculateContributionAmount(shareUnits, sharePrice);

        if (contributionAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "No shares found for user — contribution amount cannot be calculated.");
        }

        log.info("CONTRIBUTION_RECEIVED: amount={} user={}", contributionAmount, user.getFullName());

        ContributionEntity contribution = new ContributionEntity();
        contribution.setUser(user);
        contribution.setAmount(contributionAmount);
        contribution.setContributionDate(date);
        contribution.setNotes(request.notes());
        contribution.setProofOfPaymentUrl(request.proofS3Key());
        contribution.setProofFileType(request.proofFileType());
        // No proof → auto-verified; proof attached → PENDING review
        contribution.setVerificationStatus(request.proofS3Key() != null ? "PENDING" : "VERIFIED");

        ContributionEntity saved = contributionRepository.save(contribution);

        // Ledger: only post immediately if auto-verified (no proof required).
        if ("VERIFIED".equals(saved.getVerificationStatus())) {
            writeContributionLedgerEntry(user, contributionAmount, date, saved.getId());
        }

        // Loan repayment — settled atomically in the same transaction
        findActiveLoan(request.userId())
                .ifPresent(loan -> processLoanRepayment(user, loan, date));

        log.info("CONTRIBUTION_RECORDED: id={} user={} status={}",
                saved.getId(), user.getFullName(), saved.getVerificationStatus());
        return toDTO(saved);
    }

    /**
     * Marks a PENDING contribution as VERIFIED and posts the ledger entry.
     */
    @Transactional
    public ContributionDTO verify(UUID contributionId) {
        ContributionEntity c = contributionRepository.findById(contributionId)
                .orElseThrow(() -> new IllegalArgumentException("Contribution not found: " + contributionId));
        String previousStatus = c.getVerificationStatus();
        c.setVerificationStatus("VERIFIED");
        c.setRejectionReason(null);
        ContributionEntity saved = contributionRepository.save(c);

        if ("PENDING".equals(previousStatus)) {
            writeContributionLedgerEntry(saved.getUser(), saved.getAmount(), saved.getContributionDate(), saved.getId());
            log.info("CONTRIBUTION_VERIFIED: id={}", contributionId);
        }
        return toDTO(saved);
    }

    /**
     * Marks a contribution as REJECTED with a mandatory reason.
     * If previously VERIFIED, a compensating reversal entry is written to the ledger.
     */
    @Transactional
    public ContributionDTO reject(UUID contributionId, String reason) {
        ContributionEntity c = contributionRepository.findById(contributionId)
                .orElseThrow(() -> new IllegalArgumentException("Contribution not found: " + contributionId));
        String previousStatus = c.getVerificationStatus();
        c.setVerificationStatus("REJECTED");
        c.setRejectionReason(reason);
        ContributionEntity saved = contributionRepository.save(c);

        if ("VERIFIED".equals(previousStatus)) {
            writeContributionReversalLedgerEntry(saved.getUser(), saved.getAmount(), saved.getId());
            log.info("CONTRIBUTION_REVERSED: id={}", contributionId);
        }
        return toDTO(saved);
    }

    /**
     * Returns proof metadata for access-control and signed URL generation.
     */
    public ContributionProofInfoDTO getProofInfo(UUID contributionId) {
        ContributionEntity c = contributionRepository.findById(contributionId)
                .orElseThrow(() -> new IllegalArgumentException("Contribution not found: " + contributionId));
        return new ContributionProofInfoDTO(
                c.getUser().getId(),
                c.getProofOfPaymentUrl(),
                c.getProofFileType());
    }

    public ContributionDTO toDTO(ContributionEntity c) {
        return new ContributionDTO(
                c.getId(),
                c.getUser().getId(),
                c.getUser().getFullName(),
                c.getAmount(),
                c.getContributionDate(),
                c.getNotes(),
                c.getProofFileType(),
                c.getProofOfPaymentUrl() != null && !c.getProofOfPaymentUrl().isBlank(),
                c.getVerificationStatus(),
                c.getRejectionReason(),
                c.getCreatedAt()
        );
    }

    public void notifyOverdue(UUID userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        BigDecimal shareUnits    = shareRepository.sumShareUnitsByUserId(userId);
        BigDecimal sharePrice    = configService.getSharePrice();
        BigDecimal overdueAmount = FinanceUtil.calculateContributionAmount(shareUnits, sharePrice);

        log.info("CONTRIBUTION_OVERDUE: user={} amount={}", userId, overdueAmount);

        notificationEventService.publish(
                NotificationEventType.CONTRIBUTION_OVERDUE,
                new NotificationEventDetail(user.getId(), user.getPhoneNumber(), overdueAmount));
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private void writeContributionLedgerEntry(UserEntity user, BigDecimal amount, LocalDate date, UUID contributionId) {
        LedgerEntryEntity entry = new LedgerEntryEntity();
        entry.setUser(user);
        entry.setEntryType("CONTRIBUTION");
        entry.setEntryScope("USER");
        entry.setAmount(amount);
        entry.setReference(contributionId.toString());
        entry.setDescription("Member contribution — " + user.getFullName());
        entry.setPostedAt(date.atStartOfDay().atOffset(ZoneOffset.UTC));
        ledgerEntryRepository.save(entry);
    }

    private void writeContributionReversalLedgerEntry(UserEntity user, BigDecimal amount, UUID contributionId) {
        LedgerEntryEntity entry = new LedgerEntryEntity();
        entry.setUser(user);
        entry.setEntryType("CONTRIBUTION_REVERSAL");
        entry.setEntryScope("USER");
        entry.setAmount(amount.negate());
        entry.setReference(contributionId.toString());
        entry.setDescription("Contribution reversal (rejected) — " + user.getFullName());
        entry.setPostedAt(LocalDate.now().atStartOfDay().atOffset(ZoneOffset.UTC));
        ledgerEntryRepository.save(entry);
    }

    private void processLoanRepayment(UserEntity user, LoanEntity loan, LocalDate date) {
        BigDecimal interest        = FinanceUtil.calculateFlatInterest(loan.getPrincipalAmount(), loan.getInterestRate());
        BigDecimal totalRepayment  = loan.getPrincipalAmount().add(interest);
        BigDecimal loanOutstanding = totalRepayment.subtract(loan.getAmountRepaid()).max(BigDecimal.ZERO);

        if (loanOutstanding.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        BigDecimal newAmountRepaid = loan.getAmountRepaid().add(loanOutstanding);
        loan.setAmountRepaid(newAmountRepaid);
        if (newAmountRepaid.compareTo(totalRepayment) >= 0) {
            loan.setStatus("REPAID");
            log.info("LOAN_REPAID: id={} user={}", loan.getId(), user.getFullName());
        }
        loanRepository.save(loan);

        LedgerEntryEntity repayEntry = new LedgerEntryEntity();
        repayEntry.setUser(user);
        repayEntry.setEntryType("LOAN_REPAYMENT");
        repayEntry.setEntryScope("USER");
        repayEntry.setAmount(loanOutstanding);
        repayEntry.setReference(loan.getId().toString());
        repayEntry.setDescription("Loan repayment via contribution — " + user.getFullName());
        repayEntry.setPostedAt(date.atStartOfDay().atOffset(ZoneOffset.UTC));
        ledgerEntryRepository.save(repayEntry);

        log.info("LOAN_REPAYMENT_RECORDED: amount={} user={} loan={}", loanOutstanding, user.getFullName(), loan.getId());
    }

    private Optional<LoanEntity> findActiveLoan(UUID userId) {
        return loanRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .filter(l -> "ACTIVE".equals(l.getStatus()))
                .findFirst();
    }
}
