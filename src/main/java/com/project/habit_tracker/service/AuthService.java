package com.project.habit_tracker.service;

import com.project.habit_tracker.api.dto.AuthLoginRequest;
import com.project.habit_tracker.api.dto.AuthRefreshRequest;
import com.project.habit_tracker.api.dto.AuthRegisterRequest;
import com.project.habit_tracker.api.dto.AuthResponse;
import com.project.habit_tracker.security.EncryptedStringConverter;
import com.project.habit_tracker.entity.EmailVerificationCode;
import com.project.habit_tracker.entity.PasswordResetToken;
import com.project.habit_tracker.entity.RefreshToken;
import com.project.habit_tracker.entity.User;
import com.project.habit_tracker.entity.UserProfile;
import com.project.habit_tracker.repository.EmailVerificationCodeRepository;
import com.project.habit_tracker.repository.PasswordResetTokenRepository;
import com.project.habit_tracker.repository.RefreshTokenRepository;
import com.project.habit_tracker.repository.UserProfileRepository;
import com.project.habit_tracker.repository.UserRepository;
import com.project.habit_tracker.security.JwtService;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.security.SecureRandom;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String AVATAR_BASE_URL = "https://api.dicebear.com/9.x/adventurer/png?seed=";
    private static final long EMAIL_VERIFICATION_TTL_MINUTES = 15;
    private static final long RESET_TOKEN_TTL_HOURS = 24;

    private final UserRepository userRepo;
    private final UserProfileRepository profileRepo;
    private final EmailVerificationCodeRepository emailVerificationCodeRepo;
    private final PasswordResetTokenRepository resetTokenRepo;
    private final RefreshTokenRepository refreshTokenRepo;
    private final JwtService jwtService;
    private final EmailService emailService;
    private final EncryptedStringConverter encConverter;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    public AuthService(
            UserRepository userRepo,
            UserProfileRepository profileRepo,
            EmailVerificationCodeRepository emailVerificationCodeRepo,
            PasswordResetTokenRepository resetTokenRepo,
            RefreshTokenRepository refreshTokenRepo,
            JwtService jwtService,
            EmailService emailService,
            EncryptedStringConverter encConverter
    ) {
        this.userRepo = userRepo;
        this.profileRepo = profileRepo;
        this.emailVerificationCodeRepo = emailVerificationCodeRepo;
        this.resetTokenRepo = resetTokenRepo;
        this.refreshTokenRepo = refreshTokenRepo;
        this.jwtService = jwtService;
        this.emailService = emailService;
        this.encConverter = encConverter;
    }

    @Transactional
    public void requestEmailVerification(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        if (emailExists(email)) {
            throw new IllegalArgumentException("Email already registered");
        }

        emailVerificationCodeRepo.deleteByEmail(email);

        String code = "%06d".formatted(random.nextInt(1_000_000));
        EmailVerificationCode verification = EmailVerificationCode.builder()
                .email(email)
                .code(code)
                .expiresAt(Instant.now().plus(EMAIL_VERIFICATION_TTL_MINUTES, ChronoUnit.MINUTES))
                .build();
        emailVerificationCodeRepo.save(verification);

        emailService.sendEmailVerification(email, code, EMAIL_VERIFICATION_TTL_MINUTES);
    }

    @Transactional
    public AuthResponse register(AuthRegisterRequest req) {
        String username = normalizeUsername(req.username());
        String email = normalizeEmail(req.email());

        if (userRepo.existsByUsername(username) || emailExists(email)) {
            // Single generic error so an attacker can't enumerate which of the two
            // conflicts: reveals only that the combination is unavailable.
            throw new IllegalArgumentException("That username or email can't be used. Try different credentials.");
        }
        consumeEmailVerificationCode(email, req.verificationCode());

        User user = User.builder()
                .username(username)
                .email(email)
                .emailHash(encConverter.hash(email))
                .passwordHash(encoder.encode(req.password()))
                .build();
        userRepo.save(user);

        UserProfile profile = UserProfile.builder()
                .user(user)
                .displayName(username)
                .avatarUrl(normalizeAvatarUrl(req.avatarUrl(), username))
                .timezone(ZoneId.systemDefault().getId())
                .language(Locale.getDefault().getLanguage().toUpperCase(Locale.ROOT))
                .goals("Daily consistency")
                .build();
        profileRepo.save(profile);

        // Fire-and-forget welcome email — never fails the registration if email is down
        emailService.sendWelcome(email, username);

        return issueTokens(user);
    }

    public AuthResponse login(AuthLoginRequest req) {
        // `normalizeUsername` and `normalizeEmail` are identical (trim +
        // lowercase) so one normalised identifier serves both lookups.
        String identifier = normalizeUsername(req.username());
        String emailHash = encConverter.hash(identifier);
        User user = userRepo.findByUsername(identifier)
                .or(() -> userRepo.findByEmailHash(emailHash))
                .or(() -> userRepo.findByEmail(identifier))  // fallback for pre-encryption rows
                .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));

        if (!encoder.matches(req.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid credentials");
        }

        return issueTokens(user);
    }

    @Transactional
    public AuthResponse refresh(AuthRefreshRequest req) {
        String rawToken = req.refreshToken();

        // Validate JWT signature + expiry first
        Long userId = jwtService.parseRefreshToken(rawToken);

        // Check our DB record — rejects stolen tokens that were already rotated
        String hash = jwtService.hashToken(rawToken);
        RefreshToken stored = refreshTokenRepo.findByTokenHash(hash)
                .orElseThrow(() -> new IllegalArgumentException("Invalid refresh token"));

        if (stored.getUsedAt() != null) {
            // Token reuse detected — possible theft; invalidate all tokens for this user
            refreshTokenRepo.deleteByUser(stored.getUser());
            throw new IllegalArgumentException("Refresh token already used");
        }

        // Rotate: mark old token consumed
        stored.setUsedAt(Instant.now());
        refreshTokenRepo.save(stored);

        User user = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return issueTokens(user);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        String hash = jwtService.hashToken(rawRefreshToken);
        refreshTokenRepo.findByTokenHash(hash).ifPresent(stored -> {
            stored.setUsedAt(Instant.now());
            refreshTokenRepo.save(stored);
        });
        // Always succeed — don't reveal whether the token existed
    }

    /**
     * Initiates a password reset. Silently succeeds even if the account doesn't exist
     * to prevent user enumeration.
     */
    @Transactional
    public void requestPasswordReset(String emailOrUsername) {
        String normalized = emailOrUsername.trim().toLowerCase(Locale.ROOT);
        String emailHash = encConverter.hash(normalized);
        User user = userRepo.findByUsername(normalized)
                .or(() -> userRepo.findByEmailHash(emailHash))
                .or(() -> userRepo.findByEmail(normalized))  // fallback for pre-encryption rows
                .orElse(null);

        if (user == null) {
            log.info("Password reset requested for unknown account: {}", normalized);
            return;  // silent no-op — don't reveal whether the account exists
        }

        // Invalidate any existing tokens for this user
        resetTokenRepo.deleteByUser(user);

        String token = UUID.randomUUID().toString().replace("-", "");
        PasswordResetToken prt = PasswordResetToken.builder()
                .user(user)
                .token(token)
                .expiresAt(Instant.now().plus(RESET_TOKEN_TTL_HOURS, ChronoUnit.HOURS))
                .build();
        resetTokenRepo.save(prt);

        String displayName = profileRepo.findByUser(user)
                .map(p -> p.getDisplayName())
                .orElse(user.getUsername());
        emailService.sendPasswordReset(user.getEmail(), displayName, token);
    }

    /**
     * Consumes a reset token and updates the user's password.
     * Throws {@link IllegalArgumentException} for invalid/expired/already-used tokens.
     */
    @Transactional
    public AuthResponse resetPassword(String token, String newPassword) {
        PasswordResetToken prt = resetTokenRepo.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired reset token"));

        if (prt.getUsedAt() != null) {
            throw new IllegalArgumentException("This reset link has already been used");
        }
        if (Instant.now().isAfter(prt.getExpiresAt())) {
            throw new IllegalArgumentException("This reset link has expired. Please request a new one");
        }

        User user = prt.getUser();
        user.setPasswordHash(encoder.encode(newPassword));
        userRepo.save(user);

        prt.setUsedAt(Instant.now());
        resetTokenRepo.save(prt);

        return issueTokens(user);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private boolean emailExists(String normalizedEmail) {
        String hash = encConverter.hash(normalizedEmail);
        if (userRepo.existsByEmailHash(hash)) return true;
        return userRepo.existsByEmail(normalizedEmail);  // fallback for pre-encryption rows
    }

    private void consumeEmailVerificationCode(String email, String code) {
        EmailVerificationCode verification = emailVerificationCodeRepo.findTopByEmailOrderByIdDesc(email)
                .orElseThrow(() -> new IllegalArgumentException("Request an email verification code first"));

        if (verification.getUsedAt() != null) {
            throw new IllegalArgumentException("This verification code has already been used");
        }
        if (Instant.now().isAfter(verification.getExpiresAt())) {
            throw new IllegalArgumentException("This verification code has expired. Request a new one");
        }
        if (!verification.getCode().equals(code.trim())) {
            throw new IllegalArgumentException("Invalid verification code");
        }

        verification.setUsedAt(Instant.now());
        emailVerificationCodeRepo.save(verification);
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
        String accessToken  = jwtService.createAccessToken(user.getId(), user.getEmail());
        String refreshToken = jwtService.createRefreshToken(user.getId(), user.getEmail());
        long   accessExp    = jwtService.extractExpirationEpochSeconds(accessToken);
        long   refreshExp   = jwtService.extractExpirationEpochSeconds(refreshToken);

        refreshTokenRepo.save(RefreshToken.builder()
                .user(user)
                .tokenHash(jwtService.hashToken(refreshToken))
                .expiresAt(Instant.ofEpochSecond(refreshExp))
                .build());

        return new AuthResponse(accessToken, refreshToken, accessExp);
    }
}
