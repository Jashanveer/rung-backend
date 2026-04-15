package com.project.habit_tracker.api;

import com.project.habit_tracker.api.dto.AccountabilityDashboardResponse;
import com.project.habit_tracker.api.dto.MentorshipMessageRequest;
import com.project.habit_tracker.api.dto.ProfileRequest;
import com.project.habit_tracker.api.dto.SocialPostRequest;
import com.project.habit_tracker.security.JwtAuthFilter;
import com.project.habit_tracker.service.AccountabilityService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/accountability")
public class AccountabilityController {
    private final AccountabilityService accountabilityService;

    public AccountabilityController(AccountabilityService accountabilityService) {
        this.accountabilityService = accountabilityService;
    }

    @GetMapping("/dashboard")
    public ResponseEntity<AccountabilityDashboardResponse> dashboard(Authentication auth) {
        return ResponseEntity.ok(accountabilityService.dashboard(userId(auth)));
    }

    @PutMapping("/profile")
    public ResponseEntity<AccountabilityDashboardResponse> updateProfile(
            Authentication auth,
            @Valid @RequestBody ProfileRequest req
    ) {
        return ResponseEntity.ok(accountabilityService.updateProfile(userId(auth), req));
    }

    @PostMapping("/match")
    public ResponseEntity<AccountabilityDashboardResponse> assignMentor(Authentication auth) {
        return ResponseEntity.ok(accountabilityService.assignMentor(userId(auth)));
    }

    @PostMapping("/matches/{matchId}/messages")
    public ResponseEntity<AccountabilityDashboardResponse> sendMessage(
            Authentication auth,
            @PathVariable Long matchId,
            @Valid @RequestBody MentorshipMessageRequest req
    ) {
        return ResponseEntity.ok(accountabilityService.sendMessage(userId(auth), matchId, req, false));
    }

    @PostMapping("/matches/{matchId}/nudge")
    public ResponseEntity<AccountabilityDashboardResponse> sendNudge(
            Authentication auth,
            @PathVariable Long matchId
    ) {
        return ResponseEntity.ok(accountabilityService.sendNudge(userId(auth), matchId));
    }

    @GetMapping("/feed")
    public ResponseEntity<List<AccountabilityDashboardResponse.SocialPost>> feed() {
        return ResponseEntity.ok(accountabilityService.feed());
    }

    @PostMapping("/feed")
    public ResponseEntity<AccountabilityDashboardResponse> createPost(
            Authentication auth,
            @Valid @RequestBody SocialPostRequest req
    ) {
        return ResponseEntity.ok(accountabilityService.createPost(userId(auth), req));
    }

    @GetMapping("/challenge/weekly")
    public ResponseEntity<AccountabilityDashboardResponse.WeeklyChallenge> weeklyChallenge(Authentication auth) {
        return ResponseEntity.ok(accountabilityService.weeklyChallenge(userId(auth)));
    }

    private Long userId(Authentication auth) {
        return ((JwtAuthFilter.AuthPrincipal) auth.getPrincipal()).userId();
    }
}
