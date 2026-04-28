package com.project.rung.api;

import com.project.rung.api.dto.MeResponse;
import com.project.rung.security.JwtAuthFilter;
import com.project.rung.service.AccountabilityStreamService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class MeController {

    private final AccountabilityStreamService streamService;

    public MeController(AccountabilityStreamService streamService) {
        this.streamService = streamService;
    }

    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(Authentication auth) {
        JwtAuthFilter.AuthPrincipal principal = (JwtAuthFilter.AuthPrincipal) auth.getPrincipal();
        return ResponseEntity.ok(new MeResponse(principal.userId(), principal.email(), principal.username(), principal.profileSetupCompleted()));
    }

    /// Per-user SSE channel for cross-device live sync. Clients subscribe
    /// on launch; the server fires a `habits.changed` event every time a
    /// habit write (create / update / delete / setCheck) lands for this
    /// user on any device. The subscriber responds by running its normal
    /// sync pass — payload is intentionally tiny because reconciliation
    /// already handles the data fetch.
    @GetMapping(value = "/me/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamUser(
            Authentication auth,
            @RequestParam(value = "platform", required = false) String platform
    ) {
        Long userId = ((JwtAuthFilter.AuthPrincipal) auth.getPrincipal()).userId();
        return streamService.subscribeUser(userId, platform);
    }
}
