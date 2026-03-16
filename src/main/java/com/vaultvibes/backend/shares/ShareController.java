package com.vaultvibes.backend.shares;

import com.vaultvibes.backend.shares.dto.ShareDTO;
import com.vaultvibes.backend.shares.dto.ShareSummaryDTO;
import com.vaultvibes.backend.users.UserEntity;
import com.vaultvibes.backend.users.UserService;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/shares")
@RequiredArgsConstructor
@Validated
public class ShareController {

    private final ShareService shareService;
    private final UserService  userService;

    @GetMapping
    public ShareSummaryDTO getSummary() {
        return shareService.getSummary();
    }

    @GetMapping("/my")
    public List<ShareDTO> myShares() {
        UserEntity user = userService.getCurrentUser();
        return shareService.getSharesForUser(user.getId());
    }

    @PutMapping("/user/{userId}")
    public ShareDTO updateUserShares(
            @PathVariable UUID userId,
            @RequestBody UpdateSharesRequest request) {
        UserEntity user = userService.getUserById(userId);
        return shareService.updateShares(userId, request.shareUnits(), user);
    }

    public record UpdateSharesRequest(
            @Min(value = 0, message = "shareUnits must be non-negative") int shareUnits) {}
}
