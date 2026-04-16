package com.project.habit_tracker.service;

import com.project.habit_tracker.api.dto.AuthLoginRequest;
import com.project.habit_tracker.api.dto.AuthRefreshRequest;
import com.project.habit_tracker.api.dto.AuthRegisterRequest;
import com.project.habit_tracker.api.dto.AuthResponse;
import com.project.habit_tracker.entity.User;
import com.project.habit_tracker.entity.UserProfile;
import com.project.habit_tracker.repository.UserProfileRepository;
import com.project.habit_tracker.repository.UserRepository;
import com.project.habit_tracker.security.JwtService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Locale;

@Service
public class AuthService {
    private static final String AVATAR_BASE_URL = "https://api.dicebear.com/9.x/adventurer/png?seed=";

    private final UserRepository userRepo;
    private final UserProfileRepository profileRepo;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(UserRepository userRepo, UserProfileRepository profileRepo, JwtService jwtService) {
        this.userRepo = userRepo;
        this.profileRepo = profileRepo;
        this.jwtService = jwtService;
    }

    public AuthResponse register(AuthRegisterRequest req) {
        String username = normalizeUsername(req.username());
        String email = req.email().trim().toLowerCase(Locale.ROOT);

        if (userRepo.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already taken");
        }
        if (userRepo.existsByEmail(email)) {
            throw new IllegalArgumentException("Email already registered");
        }

        User user = User.builder()
                .username(username)
                .email(email)
                .passwordHash(encoder.encode(req.password()))
                .build();
        userRepo.save(user);

        profileRepo.save(UserProfile.builder()
                .user(user)
                .displayName(username)
                .avatarUrl(normalizeAvatarUrl(req.avatarUrl(), username))
                .timezone(ZoneId.systemDefault().getId())
                .language(Locale.getDefault().getLanguage().toUpperCase(Locale.ROOT))
                .goals("Daily consistency")
                .build());

        return issueTokens(user);
    }

    public AuthResponse login(AuthLoginRequest req) {
        String username = normalizeUsername(req.username());
        User user = userRepo.findByUsername(username)
                .or(() -> userRepo.findByEmail(req.username().trim().toLowerCase(Locale.ROOT)))
                .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));

        if (!encoder.matches(req.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid credentials");
        }

        return issueTokens(user);
    }

    public AuthResponse refresh(AuthRefreshRequest req) {
        Long userId = jwtService.parseRefreshToken(req.refreshToken());
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid refresh token"));
        return issueTokens(user);
    }

    private String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeAvatarUrl(String avatarUrl, String username) {
        if (avatarUrl == null || avatarUrl.isBlank()) {
            return AVATAR_BASE_URL + URLEncoder.encode(username, StandardCharsets.UTF_8);
        }

        String trimmed = avatarUrl.trim();
        if (!trimmed.startsWith(AVATAR_BASE_URL)) {
            throw new IllegalArgumentException("Choose one of the predefined avatars");
        }
        return trimmed;
    }

    private AuthResponse issueTokens(User user) {
        String accessToken = jwtService.createAccessToken(user.getId(), user.getEmail());
        String refreshToken = jwtService.createRefreshToken(user.getId(), user.getEmail());
        long accessExp = jwtService.extractExpirationEpochSeconds(accessToken);
        return new AuthResponse(accessToken, refreshToken, accessExp);
    }
}
