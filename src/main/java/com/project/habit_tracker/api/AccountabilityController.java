package com.project.habit_tracker.api;

import com.project.habit_tracker.api.dto.AccountabilityDashboardResponse;
import com.project.habit_tracker.api.dto.MentorshipMessageRequest;
import com.project.habit_tracker.api.dto.ProfileRequest;
import com.project.habit_tracker.api.dto.UseStreakFreezeRequest;
import com.project.habit_tracker.security.JwtAuthFilter;
import com.project.habit_tracker.service.AccountabilityService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

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
    public ResponseEntity<Void> markMatchRead(
            Authentication auth,
            @PathVariable Long matchId
    ) {
        accountabilityService.markMatchRead(userId(auth), matchId);
        return ResponseEntity.ok().build();
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

    @PostMapping("/streak-freeze/use")
    public ResponseEntity<AccountabilityDashboardResponse> useStreakFreeze(
            Authentication auth,
            @Valid @RequestBody UseStreakFreezeRequest req
    ) {
        return ResponseEntity.ok(accountabilityService.useStreakFreeze(userId(auth), req.dateKey()));
    }

    private Long userId(Authentication auth) {
        return ((JwtAuthFilter.AuthPrincipal) auth.getPrincipal()).userId();
    }
}
