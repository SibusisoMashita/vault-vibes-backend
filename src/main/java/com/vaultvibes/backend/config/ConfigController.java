package com.vaultvibes.backend.config;

import com.vaultvibes.backend.auth.Permission;
import com.vaultvibes.backend.auth.PermissionService;
import com.vaultvibes.backend.users.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
@Tag(name = "Config", description = "Stokvel and borrowing configuration management")
public class ConfigController {

    private final StokvelConfigRepository stokvelConfigRepository;
    private final BorrowingConfigRepository borrowingConfigRepository;
    private final PermissionService permissionService;
    private final UserService userService;

    @GetMapping("/stokvel")
    @Operation(summary = "Get current stokvel configuration")
    public StokvelConfigEntity getStokvelConfig() {
        UUID stokvelId = currentStokvelId();
        return stokvelConfigRepository.findByStokvelId(stokvelId)
                .orElseGet(() -> {
                    StokvelConfigEntity cfg = new StokvelConfigEntity();
                    cfg.setStokvelId(stokvelId);
                    return stokvelConfigRepository.save(cfg);
                });
    }

    @PutMapping("/stokvel")
    @Operation(summary = "Update stokvel configuration (total shares and share price)")
    public StokvelConfigEntity updateStokvelConfig(@RequestBody Map<String, Object> body) {
        permissionService.require(Permission.MANAGE_SHARES);
        UUID stokvelId = currentStokvelId();
        StokvelConfigEntity cfg = stokvelConfigRepository.findByStokvelId(stokvelId)
                .orElseGet(() -> {
                    StokvelConfigEntity c = new StokvelConfigEntity();
                    c.setStokvelId(stokvelId);
                    return stokvelConfigRepository.save(c);
                });
        if (body.containsKey("totalShares")) {
            cfg.setTotalShares(new BigDecimal(body.get("totalShares").toString()));
        }
        if (body.containsKey("sharePrice")) {
            cfg.setSharePrice(new BigDecimal(body.get("sharePrice").toString()));
        }
        if (body.containsKey("cycleMonths")) {
            cfg.setCycleMonths(Integer.parseInt(body.get("cycleMonths").toString()));
        }
        if (body.containsKey("monthlyContribution")) {
            cfg.setMonthlyContribution(new BigDecimal(body.get("monthlyContribution").toString()));
        }
        if (body.containsKey("cycleStartDate")) {
            cfg.setCycleStartDate(java.time.LocalDate.parse(body.get("cycleStartDate").toString()));
        }
        return stokvelConfigRepository.save(cfg);
    }

    @GetMapping("/borrowing")
    @Operation(summary = "Get current borrowing configuration")
    public BorrowingConfigEntity getBorrowingConfig() {
        UUID stokvelId = currentStokvelId();
        return borrowingConfigRepository.findByStokvelId(stokvelId)
                .orElseGet(() -> {
                    BorrowingConfigEntity cfg = new BorrowingConfigEntity();
                    cfg.setStokvelId(stokvelId);
                    return borrowingConfigRepository.save(cfg);
                });
    }

    @PutMapping("/borrowing")
    @Operation(summary = "Update borrowing configuration (interest rate)")
    public BorrowingConfigEntity updateBorrowingConfig(@RequestBody Map<String, Object> body) {
        permissionService.require(Permission.MANAGE_SHARES);
        UUID stokvelId = currentStokvelId();
        BorrowingConfigEntity cfg = borrowingConfigRepository.findByStokvelId(stokvelId)
                .orElseGet(() -> {
                    BorrowingConfigEntity c = new BorrowingConfigEntity();
                    c.setStokvelId(stokvelId);
                    return borrowingConfigRepository.save(c);
                });
        if (body.containsKey("interestRate")) {
            cfg.setInterestRate(new BigDecimal(body.get("interestRate").toString()));
        }
        return borrowingConfigRepository.save(cfg);
    }

    private UUID currentStokvelId() {
        return userService.getCurrentUser().getStokvelId();
    }
}
