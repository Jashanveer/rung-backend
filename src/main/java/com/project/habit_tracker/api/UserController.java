package com.project.habit_tracker.api;

import com.project.habit_tracker.security.JwtAuthFilter;
import com.project.habit_tracker.service.UserService;
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

    @DeleteMapping("/me")
    public ResponseEntity<Map<String, Object>> deleteAccount(Authentication auth) {
        Long userId = ((JwtAuthFilter.AuthPrincipal) auth.getPrincipal()).userId();
        userService.deleteAccount(userId);
        return ResponseEntity.ok(Map.of());
    }
}
