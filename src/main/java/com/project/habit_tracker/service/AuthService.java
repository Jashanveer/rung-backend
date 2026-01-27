package com.project.habit_tracker.service;

import com.project.habit_tracker.api.dto.AuthLoginRequest;
import com.project.habit_tracker.api.dto.AuthRegisterRequest;
import com.project.habit_tracker.api.dto.AuthResponse;
import com.project.habit_tracker.entity.User;
import com.project.habit_tracker.repository.UserRepository;
import com.project.habit_tracker.security.JwtService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final UserRepository userRepo;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(UserRepository userRepo, JwtService jwtService) {
        this.userRepo = userRepo;
        this.jwtService = jwtService;
    }

    public AuthResponse register(AuthRegisterRequest req) {
        if (userRepo.existsByEmail(req.email())) {
            throw new IllegalArgumentException("Email already registered");
        }
        User user = User.builder()
                .email(req.email().toLowerCase())
                .passwordHash(encoder.encode(req.password()))
                .build();
        userRepo.save(user);

        String token = jwtService.createToken(user.getId(), user.getEmail());
        return new AuthResponse(token);
    }

    public AuthResponse login(AuthLoginRequest req) {
        User user = userRepo.findByEmail(req.email().toLowerCase())
                .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));

        if (!encoder.matches(req.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid credentials");
        }

        String token = jwtService.createToken(user.getId(), user.getEmail());
        return new AuthResponse(token);
    }
}
