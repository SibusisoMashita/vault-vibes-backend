package com.vaultvibes.backend.ledger;

import com.vaultvibes.backend.auth.Permission;
import com.vaultvibes.backend.auth.PermissionService;
import com.vaultvibes.backend.ledger.dto.BankInterestRequestDTO;
import com.vaultvibes.backend.ledger.dto.LedgerEntryDTO;
import com.vaultvibes.backend.users.UserEntity;
import com.vaultvibes.backend.users.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ledger")
@RequiredArgsConstructor
@Tag(name = "Ledger", description = "Transaction ledger and pool-level income")
public class LedgerController {

    private final LedgerService ledgerService;
    private final PermissionService permissionService;
    private final UserService userService;

    @GetMapping
    @Operation(summary = "List ledger entries — admins see all entries, members see only their own")
    public List<LedgerEntryDTO> list() {
        if (permissionService.currentUserHas(Permission.AUDIT_LEDGER)) {
            return ledgerService.listAll();
        }
        UserEntity currentUser = userService.getCurrentUser();
        return ledgerService.listForUser(currentUser.getId());
    }

    @PostMapping("/bank-interest")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record bank interest earned by the pool",
               description = "Creates a BANK_INTEREST ledger entry. Not tied to any member. Increases pool liquidity.")
    public LedgerEntryDTO recordBankInterest(@Valid @RequestBody BankInterestRequestDTO request) {
        permissionService.require(Permission.RECORD_BANK_INTEREST);
        return ledgerService.recordBankInterest(request);
    }
}
