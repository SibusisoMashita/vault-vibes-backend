package com.vaultvibes.backend.dashboard;

import com.vaultvibes.backend.dashboard.dto.DashboardSummaryDTO;
import com.vaultvibes.backend.users.UserEntity;
import com.vaultvibes.backend.users.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final UserService userService;

    @GetMapping("/summary")
    public DashboardSummaryDTO summary() {
        UserEntity user = userService.getCurrentUser();
        return dashboardService.getSummary(user);
    }
}
