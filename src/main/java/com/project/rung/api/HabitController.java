package com.project.rung.api;

import com.project.rung.service.AIService;
import com.project.rung.api.dto.CheckUpdateRequest;
import com.project.rung.api.dto.HabitCreateRequest;
import com.project.rung.api.dto.HabitResponse;
import com.project.rung.api.dto.HabitUpdateRequest;
import com.project.rung.api.dto.ParseFrequencyRequest;
import com.project.rung.api.dto.ParseFrequencyResponse;
import com.project.rung.security.JwtAuthFilter;
import com.project.rung.service.HabitService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/habits")
public class HabitController {
    private final HabitService habitService;
    private final AIService aiService;

    public HabitController(HabitService habitService, AIService aiService) {
        this.habitService = habitService;
        this.aiService = aiService;
    }

    private Long userId(Authentication auth) {
        return ((JwtAuthFilter.AuthPrincipal) auth.getPrincipal()).userId();
    }

    @GetMapping
    public ResponseEntity<List<HabitResponse>> list(Authentication auth) {
        return ResponseEntity.ok(habitService.listHabits(userId(auth)));
    }

    @PostMapping
    public ResponseEntity<HabitResponse> create(Authentication auth, @Valid @RequestBody HabitCreateRequest req) {
        return ResponseEntity.ok(habitService.createHabit(userId(auth), req));
    }

    /**
     * LLM fallback for frequency parsing — only called by the client when
     * the local regex pass missed but the input contains hints suggesting
     * a cadence ("week", "every", numeric mention, etc). Returns the
     * user's original text with {@code didMatch=false} on any failure so
     * the client never blocks waiting on the AI service.
     */
    @PostMapping("/parse-frequency")
    public ResponseEntity<ParseFrequencyResponse> parseFrequency(
            Authentication auth,
            @Valid @RequestBody ParseFrequencyRequest req
    ) {
        // Auth guard — `auth` will be non-null because the route lives
        // behind the JWT filter; the unused parameter is just there to
        // match the signature pattern used by every other route.
        userId(auth);

        return aiService.parseHabitFrequency(req.text())
                .map(result -> ResponseEntity.ok(new ParseFrequencyResponse(
                        result.cleanedTitle(),
                        result.weeklyTarget(),
                        true
                )))
                .orElseGet(() -> ResponseEntity.ok(new ParseFrequencyResponse(
                        req.text(),
                        null,
                        false
                )));
    }

    @PutMapping("/{habitId}")
    public ResponseEntity<HabitResponse> update(Authentication auth,
                                                @PathVariable Long habitId,
                                                @Valid @RequestBody HabitUpdateRequest req) {
        return ResponseEntity.ok(habitService.updateHabit(userId(auth), habitId, req));
    }

    @DeleteMapping("/{habitId}")
    public ResponseEntity<Map<String, Object>> delete(Authentication auth, @PathVariable Long habitId) {
        habitService.deleteHabit(userId(auth), habitId);
        // Return an empty JSON object instead of 204 so clients that always
        // attempt to decode a JSON body succeed (Rung macOS/iOS).
        return ResponseEntity.ok(Map.of());
    }

    @PutMapping("/{habitId}/checks/{dateKey}")
    public ResponseEntity<HabitResponse> setCheck(Authentication auth,
                                                  @PathVariable Long habitId,
                                                  @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "dateKey must be yyyy-MM-dd")
                                                  @PathVariable String dateKey,
                                                  @Valid @RequestBody CheckUpdateRequest req) {

        return ResponseEntity.ok(habitService.setCheck(
                userId(auth), habitId, dateKey, req.done(),
                req.verificationTier(), req.verificationSource()
        ));
    }
}
