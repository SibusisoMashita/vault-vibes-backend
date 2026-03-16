package com.vaultvibes.backend.loans;

import com.vaultvibes.backend.auth.Permission;
import com.vaultvibes.backend.auth.PermissionService;
import com.vaultvibes.backend.loans.dto.LoanDTO;
import com.vaultvibes.backend.loans.dto.LoanRequestDTO;
import com.vaultvibes.backend.users.UserEntity;
import com.vaultvibes.backend.users.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/loans")
@RequiredArgsConstructor
@Tag(name = "Borrowing", description = "Group borrowing requests and approvals")
public class LoanController {

    private final LoanService loanService;
    private final PermissionService permissionService;
    private final UserService userService;

    @GetMapping
    @Operation(summary = "List borrowing records — admins see all members, members see only their own")
    public List<LoanDTO> list() {
        if (permissionService.currentUserHas(Permission.ISSUE_LOAN)) {
            return loanService.listAll();
        }
        UserEntity currentUser = userService.getCurrentUser();
        return loanService.listForUser(currentUser.getId());
    }

    @PostMapping("/request")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Submit a new borrowing request")
    public LoanDTO requestLoan(@Valid @RequestBody LoanRequestDTO request) {
        return loanService.requestLoan(request);
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve a pending borrowing request")
    public LoanDTO approve(@PathVariable UUID id) {
        permissionService.require(Permission.ISSUE_LOAN);
        return loanService.approveLoan(id);
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject a pending borrowing request")
    public LoanDTO reject(@PathVariable UUID id) {
        permissionService.require(Permission.ISSUE_LOAN);
        return loanService.rejectLoan(id);
    }

    @PostMapping("/{id}/repay")
    @Operation(summary = "Mark a borrowing as fully repaid")
    public LoanDTO repay(@PathVariable UUID id) {
        permissionService.require(Permission.RECORD_REPAYMENT);
        return loanService.markRepaid(id);
    }
}
