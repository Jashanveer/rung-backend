package com.project.rung.api;

import com.project.rung.api.dto.SleepSnapshotRequest;
import com.project.rung.api.dto.SleepSnapshotResponse;
import com.project.rung.security.JwtAuthFilter;
import com.project.rung.service.SleepSnapshotService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Cross-device sleep-snapshot sync. iOS uploads via {@code POST}, every
 * client (including macOS, which can't read HealthKit natively) reads
 * via {@code GET}. The shape mirrors the Swift {@code SleepSnapshot}
 * struct so both sides decode the same payload.
 */
@RestController
@RequestMapping("/api/sleep")
public class SleepController {

    private final SleepSnapshotService service;

    public SleepController(SleepSnapshotService service) {
        this.service = service;
    }

    private Long userId(Authentication auth) {
        return ((JwtAuthFilter.AuthPrincipal) auth.getPrincipal()).userId();
    }

    /**
     * Push the latest snapshot for the authenticated user. Idempotent:
     * one row per user, overwritten in-place.
     */
    @PostMapping("/snapshot")
    public ResponseEntity<SleepSnapshotResponse> upload(
            Authentication auth,
            @Valid @RequestBody SleepSnapshotRequest req
    ) {
        return ResponseEntity.ok(service.upload(userId(auth), req));
    }

    /**
     * Read the snapshot for the authenticated user. Returns 204 when no
     * snapshot has been uploaded yet — clients render the empty state in
     * that case ("Open Rung on iPhone to start tracking sleep").
     */
    @GetMapping("/snapshot")
    public ResponseEntity<SleepSnapshotResponse> get(Authentication auth) {
        return service.get(userId(auth))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
