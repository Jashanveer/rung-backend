package com.project.habit_tracker.api;

import com.project.habit_tracker.api.dto.UserPreferencesRequest;
import com.project.habit_tracker.api.dto.UserPreferencesResponse;
import com.project.habit_tracker.security.JwtAuthFilter;
import com.project.habit_tracker.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /// Returns 200 with an empty JSON object (rather than 204 No Content) so
    /// clients that always decode a JSON body — e.g. the Forma macOS/iOS
    /// `authorizedRequest` helper — succeed instead of failing with
    /// `invalidResponse` on an empty payload.
    @DeleteMapping("/me")
    public ResponseEntity<Map<String, Object>> deleteAccount(Authentication auth) {
        Long userId = ((JwtAuthFilter.AuthPrincipal) auth.getPrincipal()).userId();
        userService.deleteAccount(userId);
        return ResponseEntity.ok(Map.of());
    }

    @GetMapping("/me/preferences")
    public ResponseEntity<UserPreferencesResponse> getPreferences(Authentication auth) {
        return ResponseEntity.ok(userService.getPreferences(userId(auth)));
    }

    @PutMapping("/me/preferences")
    public ResponseEntity<UserPreferencesResponse> updatePreferences(
            Authentication auth,
            @Valid @RequestBody UserPreferencesRequest req
    ) {
        return ResponseEntity.ok(userService.updatePreferences(userId(auth), req.emailOptIn()));
    }

    private Long userId(Authentication auth) {
        return ((JwtAuthFilter.AuthPrincipal) auth.getPrincipal()).userId();
    }
}
