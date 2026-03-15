package com.vaultvibes.backend.pool;

import com.vaultvibes.backend.pool.dto.PoolProjectionDTO;
import com.vaultvibes.backend.pool.dto.PoolStatsDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pool")
@RequiredArgsConstructor
@Tag(name = "Pool", description = "Pool balance and year-end projection")
public class PoolController {

    private final PoolService           poolService;
    private final PoolProjectionService poolProjectionService;

    @GetMapping("/stats")
    @Operation(summary = "Current pool state derived from the ledger")
    public PoolStatsDTO stats() {
        return poolService.getStats();
    }

    @GetMapping("/projection")
    @Operation(summary = "Year-end distribution projection",
               description = "Projects the pool value at year-end by adding expected contributions, loan interest, and estimated bank interest (based on 3-month historical average) to the current pool value.")
    public PoolProjectionDTO projection() {
        return poolProjectionService.getProjection();
    }
}
