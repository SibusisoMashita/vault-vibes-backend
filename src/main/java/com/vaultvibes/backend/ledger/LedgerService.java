package com.vaultvibes.backend.ledger;

import com.vaultvibes.backend.ledger.dto.BankInterestRequestDTO;
import com.vaultvibes.backend.ledger.dto.LedgerEntryDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class LedgerService {

    private final LedgerEntryRepository ledgerEntryRepository;

    public List<LedgerEntryDTO> listAll() {
        return ledgerEntryRepository.findAllByOrderByPostedAtDesc().stream()
                .map(this::toDTO)
                .toList();
    }

    public List<LedgerEntryDTO> listForUser(UUID userId) {
        return ledgerEntryRepository.findByUserIdOrderByPostedAtDesc(userId).stream()
                .map(this::toDTO)
                .toList();
    }

    @Transactional
    public LedgerEntryDTO recordBankInterest(BankInterestRequestDTO request) {
        LedgerEntryEntity entry = new LedgerEntryEntity();
        entry.setUser(null);   // pool-level income — not tied to any member
        entry.setEntryType("BANK_INTEREST");
        entry.setAmount(request.amount());
        entry.setReference(request.reference());
        entry.setDescription(request.description());
        entry.setPostedAt(request.postedAt().atStartOfDay().atOffset(ZoneOffset.UTC));

        LedgerEntryEntity saved = ledgerEntryRepository.save(entry);
        log.info("Bank interest recorded: amount={} postedAt={} ref={}",
                request.amount(), request.postedAt(), request.reference());
        return toDTO(saved);
    }

    public LedgerEntryDTO toDTO(LedgerEntryEntity entry) {
        UUID userId     = entry.getUser() != null ? entry.getUser().getId()       : null;
        String fullName = entry.getUser() != null ? entry.getUser().getFullName() : null;

        return new LedgerEntryDTO(
                entry.getId(),
                userId,
                fullName,
                entry.getEntryType(),
                entry.getAmount(),
                entry.getReference(),
                entry.getDescription(),
                entry.getPostedAt()
        );
    }
}
