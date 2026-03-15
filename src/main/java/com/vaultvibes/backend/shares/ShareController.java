package com.vaultvibes.backend.shares;

import com.vaultvibes.backend.shares.dto.ShareDTO;
import com.vaultvibes.backend.shares.dto.ShareSummaryDTO;
import com.vaultvibes.backend.users.UserEntity;
import com.vaultvibes.backend.users.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/shares")
@RequiredArgsConstructor
public class ShareController {

    private final ShareService shareService;
    private final UserService userService;

    @GetMapping
    public ShareSummaryDTO getSummary() {
        return shareService.getSummary();
    }

    @GetMapping("/my")
    public List<ShareDTO> myShares() {
        UserEntity user = userService.getCurrentUser();
        return shareService.getSharesForUser(user.getId());
    }
}
