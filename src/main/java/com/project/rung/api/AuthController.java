package com.project.rung.api;

import com.project.rung.api.dto.*;
import com.project.rung.security.AuthRateLimiter;
import com.project.rung.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final AuthRateLimiter rateLimiter;

    public AuthController(AuthService authService, AuthRateLimiter rateLimiter) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody AuthRegisterRequest req,
            HttpServletRequest httpReq
    ) {
        rateLimiter.checkRegister(clientIp(httpReq));
        return ResponseEntity.ok(authService.register(req));
    }

    @PostMapping("/email-verification")
    public ResponseEntity<Map<String, String>> requestEmailVerification(
            @Valid @RequestBody EmailVerificationRequest req,
            HttpServletRequest httpReq
    ) {
        rateLimiter.checkEmailVerification(clientIp(httpReq));
        authService.requestEmailVerification(req.email());
        return ResponseEntity.ok(Map.of("message", "Verification code sent."));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody AuthLoginRequest req,
            HttpServletRequest httpReq
    ) {
        rateLimiter.checkLogin(clientIp(httpReq));
        return ResponseEntity.ok(authService.login(req));
    }

    /**
     * Sign in with Apple — exchanges a verified Apple identity token for
     * Rung's own access + refresh JWT pair. Same rate-limit budget as
     * the password login because both surface the same auth.
     */
    @PostMapping("/apple")
    public ResponseEntity<AuthResponse> appleLogin(
            @Valid @RequestBody AppleLoginRequest req,
            HttpServletRequest httpReq
    ) {
        rateLimiter.checkLogin(clientIp(httpReq));
        return ResponseEntity.ok(authService.appleLogin(req));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @Valid @RequestBody AuthRefreshRequest req,
            HttpServletRequest httpReq
    ) {
        rateLimiter.checkRefresh(clientIp(httpReq));
        return ResponseEntity.ok(authService.refresh(req));
    }

    /**
     * Initiates a password reset. Always returns 200 to prevent user enumeration.
     * If the account exists, an email with a reset link is sent.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest req,
            HttpServletRequest httpReq
    ) {
        rateLimiter.checkForgotPassword(clientIp(httpReq));
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
            @Valid @RequestBody ResetPasswordRequest req,
            HttpServletRequest httpReq
    ) {
        rateLimiter.checkResetPassword(clientIp(httpReq));
        return ResponseEntity.ok(authService.resetPassword(req.token(), req.newPassword()));
    }

    /** Invalidates the supplied refresh token. Always returns 204. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody AuthRefreshRequest req) {
        authService.logout(req.refreshToken());
        return ResponseEntity.noContent().build();
    }

    private String clientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        return (forwarded != null && !forwarded.isBlank())
                ? forwarded.split(",")[0].trim()
                : req.getRemoteAddr();
    }
}
