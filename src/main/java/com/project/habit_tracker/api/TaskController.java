package com.project.habit_tracker.api;

import com.project.habit_tracker.api.dto.CheckUpdateRequest;
import com.project.habit_tracker.api.dto.TaskCreateRequest;
import com.project.habit_tracker.api.dto.TaskResponse;
import com.project.habit_tracker.api.dto.TaskUpdateRequest;
import com.project.habit_tracker.security.JwtAuthFilter;
import com.project.habit_tracker.service.HabitService;
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
@RequestMapping("/api/tasks")
public class TaskController {
    private final HabitService habitService;

    public TaskController(HabitService habitService) {
        this.habitService = habitService;
    }

    private Long userId(Authentication auth) {
        return ((JwtAuthFilter.AuthPrincipal) auth.getPrincipal()).userId();
    }

    @GetMapping
    public ResponseEntity<List<TaskResponse>> list(Authentication auth) {
        return ResponseEntity.ok(habitService.listTasks(userId(auth)));
    }

    @PostMapping
    public ResponseEntity<TaskResponse> create(Authentication auth, @Valid @RequestBody TaskCreateRequest req) {
        return ResponseEntity.ok(habitService.createTask(userId(auth), req));
    }

    @PutMapping("/{taskId}")
    public ResponseEntity<TaskResponse> update(Authentication auth,
                                               @PathVariable Long taskId,
                                               @Valid @RequestBody TaskUpdateRequest req) {
        return ResponseEntity.ok(habitService.updateTask(userId(auth), taskId, req));
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<Map<String, Object>> delete(Authentication auth, @PathVariable Long taskId) {
        habitService.deleteTask(userId(auth), taskId);
        // Return an empty JSON object instead of 204 so clients that always
        // attempt to decode a JSON body succeed (Forma macOS/iOS).
        return ResponseEntity.ok(Map.of());
    }

    @PutMapping("/{taskId}/checks/{dateKey}")
    public ResponseEntity<TaskResponse> setCheck(Authentication auth,
                                                 @PathVariable Long taskId,
                                                 @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "dateKey must be yyyy-MM-dd")
                                                 @PathVariable String dateKey,
                                                 @Valid @RequestBody CheckUpdateRequest req) {
        return ResponseEntity.ok(habitService.setTaskCheck(userId(auth), taskId, dateKey, req.done()));
    }
}
