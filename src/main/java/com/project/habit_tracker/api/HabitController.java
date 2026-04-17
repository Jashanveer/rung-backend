package com.project.habit_tracker.api;

import com.project.habit_tracker.api.dto.CheckUpdateRequest;
import com.project.habit_tracker.api.dto.HabitCreateRequest;
import com.project.habit_tracker.api.dto.HabitResponse;
import com.project.habit_tracker.security.JwtAuthFilter;
import com.project.habit_tracker.service.HabitService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/habits")
public class HabitController {
    private final HabitService habitService;

    public HabitController(HabitService habitService) {
        this.habitService = habitService;
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

    @DeleteMapping("/{habitId}")
    public ResponseEntity<Map<String, Object>> delete(Authentication auth, @PathVariable Long habitId) {
        habitService.deleteHabit(userId(auth), habitId);
        return ResponseEntity.ok(Map.of());
    }

    @PutMapping("/{habitId}/checks/{dateKey}")
    public ResponseEntity<HabitResponse> setCheck(Authentication auth,
                                                  @PathVariable Long habitId,
                                                  @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "dateKey must be yyyy-MM-dd")
                                                  @PathVariable String dateKey,
                                                  @Valid @RequestBody CheckUpdateRequest req) {

        return ResponseEntity.ok(habitService.setCheck(userId(auth), habitId, dateKey, req.done()));
    }
}
