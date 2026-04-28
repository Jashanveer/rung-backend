package com.project.rung.api;

import com.project.rung.api.dto.AccountabilityDashboardResponse;
import com.project.rung.api.dto.MentorshipMessageRequest;
import com.project.rung.api.dto.ProfileRequest;
import com.project.rung.api.dto.UseStreakFreezeRequest;
import com.project.rung.security.JwtAuthFilter;
import com.project.rung.service.AccountabilityService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Validated
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

    @GetMapping(value = "/matches/{matchId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMatch(
            Authentication auth,
            @PathVariable Long matchId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        return accountabilityService.streamMatch(userId(auth), matchId, lastEventId);
    }

    @PostMapping("/matches/{matchId}/read")
    public ResponseEntity<Map<String, Object>> markMatchRead(
            Authentication auth,
            @PathVariable Long matchId
    ) {
        accountabilityService.markMatchRead(userId(auth), matchId);
        // Empty JSON object instead of bare 200 so the Rung client decoder
        // (which always tries to decode a body) doesn't fail with invalidResponse.
        return ResponseEntity.ok(Map.of());
    }

    @PostMapping("/matches/{matchId}/release")
    public ResponseEntity<AccountabilityDashboardResponse> releaseMatch(
            Authentication auth,
            @PathVariable Long matchId
    ) {
        return ResponseEntity.ok(accountabilityService.releaseMatch(userId(auth), matchId));
    }

    @PostMapping("/friends/{friendUserId}")
    public ResponseEntity<AccountabilityDashboardResponse> requestFriend(
            Authentication auth,
            @PathVariable Long friendUserId
    ) {
        return ResponseEntity.ok(accountabilityService.requestFriend(userId(auth), friendUserId));
    }

    @PostMapping("/follows/{followedUserId}")
    public ResponseEntity<AccountabilityDashboardResponse> followUser(
            Authentication auth,
            @PathVariable Long followedUserId
    ) {
        return ResponseEntity.ok(accountabilityService.requestFriend(userId(auth), followedUserId));
    }

    @GetMapping("/friends/search")
    public ResponseEntity<List<AccountabilityDashboardResponse.FriendSummary>> searchFriends(
            Authentication auth,
            @RequestParam(name = "q", defaultValue = "") String query
    ) {
        return ResponseEntity.ok(accountabilityService.searchFriends(userId(auth), query));
    }

    @GetMapping("/follows/search")
    public ResponseEntity<List<AccountabilityDashboardResponse.FriendSummary>> searchFollows(
            Authentication auth,
            @RequestParam(name = "q", defaultValue = "") String query
    ) {
        return ResponseEntity.ok(accountabilityService.searchFriends(userId(auth), query));
    }

    @PostMapping("/streak-freeze/use")
    public ResponseEntity<AccountabilityDashboardResponse> useStreakFreeze(
            Authentication auth,
            @Valid @RequestBody UseStreakFreezeRequest req
    ) {
        return ResponseEntity.ok(accountabilityService.useStreakFreeze(userId(auth), req.dateKey()));
    }

    @PostMapping("/streak-freeze/undo")
    public ResponseEntity<AccountabilityDashboardResponse> undoStreakFreeze(Authentication auth) {
        return ResponseEntity.ok(accountabilityService.undoStreakFreeze(userId(auth)));
    }

    /// Returns the target user's perfect-day count for the current calendar year.
    /// Self-lookup is always allowed; otherwise the caller must follow the target.
    @GetMapping("/users/{userId}/year-perfect-days")
    public ResponseEntity<Map<String, Object>> yearPerfectDays(
            Authentication auth,
            @PathVariable("userId") Long userId
    ) {
        int count = accountabilityService.yearPerfectDays(userId(auth), userId);
        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "year", LocalDate.now().getYear(),
                "count", count
        ));
    }

    private Long userId(Authentication auth) {
        return ((JwtAuthFilter.AuthPrincipal) auth.getPrincipal()).userId();
    }
}
