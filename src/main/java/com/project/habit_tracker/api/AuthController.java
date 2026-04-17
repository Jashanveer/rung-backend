package com.project.habit_tracker.api;

import com.project.habit_tracker.api.dto.*;
import com.project.habit_tracker.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody AuthRegisterRequest req) {
        return ResponseEntity.ok(authService.register(req));
    }

    @PostMapping("/email-verification")
    public ResponseEntity<Map<String, String>> requestEmailVerification(
            @Valid @RequestBody EmailVerificationRequest req
    ) {
        authService.requestEmailVerification(req.email());
        return ResponseEntity.ok(Map.of(
                "message", "Verification code sent."
        ));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody AuthLoginRequest req) {
        return ResponseEntity.ok(authService.login(req));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody AuthRefreshRequest req) {
        return ResponseEntity.ok(authService.refresh(req));
    }

    /**
     * Initiates a password reset. Always returns 200 to prevent user enumeration.
     * If the account exists, an email with a reset link is sent.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest req
    ) {
        authService.requestPasswordReset(req.emailOrUsername());
        return ResponseEntity.ok(Map.of(
                "message", "If that account exists, a reset email has been sent."
        ));
    }

    /**
     * Consumes a reset token and updates the password.
     * Returns fresh auth tokens so the user is immediately signed in.
     */
    @PostMapping("/reset-password")
    public ResponseEntity<AuthResponse> resetPassword(
            @Valid @RequestBody ResetPasswordRequest req
    ) {
        return ResponseEntity.ok(authService.resetPassword(req.token(), req.newPassword()));
    }
}
