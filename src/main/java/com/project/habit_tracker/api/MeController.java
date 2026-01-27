package com.project.habit_tracker.api;

import com.project.habit_tracker.api.dto.MeResponse;
import com.project.habit_tracker.security.JwtAuthFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class MeController {

    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(Authentication auth) {
        JwtAuthFilter.AuthPrincipal principal = (JwtAuthFilter.AuthPrincipal) auth.getPrincipal();
        return ResponseEntity.ok(new MeResponse(principal.userId(), principal.email()));
    }
}
