package com.project.rung.api;

import com.project.rung.api.dto.MeResponse;
import com.project.rung.api.dto.ProfileSetupRequest;
import com.project.rung.api.dto.UserPreferencesRequest;
import com.project.rung.api.dto.UserPreferencesResponse;
import com.project.rung.security.JwtAuthFilter;
import com.project.rung.service.UserService;
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
    /// clients that always decode a JSON body — e.g. the Rung macOS/iOS
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

    /**
     * One-time post-Apple-signup setup: the client submits the username
     * + avatar the user picked on the profile-setup screen, the server
     * persists them and returns the refreshed MeResponse so the client
     * can update its local cache. Doubles as a "rename" endpoint after
     * first use — calling again with a new username overwrites.
     */
    @PostMapping("/me/setup-profile")
    public ResponseEntity<MeResponse> setupProfile(
            Authentication auth,
            @Valid @RequestBody ProfileSetupRequest req
    ) {
        return ResponseEntity.ok(userService.setupProfile(userId(auth), req));
    }

    /**
     * Live availability probe for the setup-profile screen. Returns
     * `{ "available": true | false }`. Currently-owned username always
     * reads as available to the calling user.
     */
    @GetMapping("/me/username-available")
    public ResponseEntity<Map<String, Boolean>> usernameAvailable(
            Authentication auth,
            @RequestParam("username") String username
    ) {
        boolean available = userService.isUsernameAvailable(userId(auth), username);
        return ResponseEntity.ok(Map.of("available", available));
    }

    private Long userId(Authentication auth) {
        return ((JwtAuthFilter.AuthPrincipal) auth.getPrincipal()).userId();
    }
}
