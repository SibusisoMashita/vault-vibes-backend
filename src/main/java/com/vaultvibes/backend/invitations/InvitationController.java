package com.vaultvibes.backend.invitations;

import com.vaultvibes.backend.auth.Permission;
import com.vaultvibes.backend.auth.PermissionService;
import com.vaultvibes.backend.invitations.dto.InvitationDTO;
import com.vaultvibes.backend.invitations.dto.InvitationRequestDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/invitations")
@RequiredArgsConstructor
public class InvitationController {

    private final InvitationService invitationService;
    private final PermissionService permissionService;

    @GetMapping
    public List<InvitationDTO> list() {
        return invitationService.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationDTO create(@Valid @RequestBody InvitationRequestDTO request) {
        permissionService.require(Permission.INVITE_MEMBER);
        return invitationService.createInvitation(request.phoneNumber(), request.role(), request.shareUnits());
    }

    @PostMapping("/{id}/resend")
    public InvitationDTO resend(@PathVariable UUID id) {
        permissionService.require(Permission.INVITE_MEMBER);
        return invitationService.resendInvitation(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        permissionService.require(Permission.INVITE_MEMBER);
        invitationService.deleteInvitation(id);
    }
}
