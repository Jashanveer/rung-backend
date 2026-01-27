package com.project.habit_tracker.api;

import com.project.habit_tracker.api.dto.CheckUpdateRequest;
import com.project.habit_tracker.api.dto.HabitCreateRequest;
import com.project.habit_tracker.api.dto.HabitResponse;
import com.project.habit_tracker.api.dto.HabitUpdateRequest;
import com.project.habit_tracker.security.JwtAuthFilter;
import com.project.habit_tracker.service.HabitService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    @PutMapping("/{habitId}")
    public ResponseEntity<HabitResponse> update(Authentication auth, @PathVariable Long habitId,
                                                @Valid @RequestBody HabitUpdateRequest req) {
        return ResponseEntity.ok(habitService.updateHabitTitle(userId(auth), habitId, req));
    }

    @DeleteMapping("/{habitId}")
    public ResponseEntity<Void> delete(Authentication auth, @PathVariable Long habitId) {
        habitService.deleteHabit(userId(auth), habitId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{habitId}/checks/{dateKey}")
    public ResponseEntity<HabitResponse> setCheck(Authentication auth,
                                                  @PathVariable Long habitId,
                                                  @PathVariable String dateKey,
                                                  @Valid @RequestBody CheckUpdateRequest req) {

        return ResponseEntity.ok(habitService.setCheck(userId(auth), habitId, dateKey, req.done()));
    }
}
