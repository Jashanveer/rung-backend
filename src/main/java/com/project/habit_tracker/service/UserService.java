package com.project.habit_tracker.service;

import com.project.habit_tracker.api.dto.MeResponse;
import com.project.habit_tracker.api.dto.ProfileSetupRequest;
import com.project.habit_tracker.api.dto.UserPreferencesResponse;
import com.project.habit_tracker.entity.Habit;
import com.project.habit_tracker.entity.HabitEntryType;
import com.project.habit_tracker.entity.HabitCheck;
import com.project.habit_tracker.entity.User;
import com.project.habit_tracker.entity.UserProfile;
import com.project.habit_tracker.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Service
public class UserService {

    private final UserRepository userRepo;
    private final UserProfileRepository profileRepo;
    private final HabitRepository habitRepo;
    private final HabitCheckRepository habitCheckRepo;
    private final RewardGrantRepository rewardGrantRepo;
    private final DeviceTokenRepository deviceTokenRepo;
    private final RefreshTokenRepository refreshTokenRepo;
    private final StreakFreezeRepository streakFreezeRepo;
    private final PasswordResetTokenRepository passwordResetTokenRepo;
    private final FriendConnectionRepository friendConnectionRepo;
    private final MentorMatchRepository mentorMatchRepo;
    private final MentorshipMessageRepository mentorshipMessageRepo;
    private final SocialPostRepository socialPostRepo;
    private final EmailVerificationCodeRepository emailVerificationCodeRepo;
    private final EmailService emailService;

    public UserService(
            UserRepository userRepo,
            UserProfileRepository profileRepo,
            HabitRepository habitRepo,
            HabitCheckRepository habitCheckRepo,
            RewardGrantRepository rewardGrantRepo,
            DeviceTokenRepository deviceTokenRepo,
            RefreshTokenRepository refreshTokenRepo,
            StreakFreezeRepository streakFreezeRepo,
            PasswordResetTokenRepository passwordResetTokenRepo,
            FriendConnectionRepository friendConnectionRepo,
            MentorMatchRepository mentorMatchRepo,
            MentorshipMessageRepository mentorshipMessageRepo,
            SocialPostRepository socialPostRepo,
            EmailVerificationCodeRepository emailVerificationCodeRepo,
            EmailService emailService
    ) {
        this.userRepo = userRepo;
        this.profileRepo = profileRepo;
        this.habitRepo = habitRepo;
        this.habitCheckRepo = habitCheckRepo;
        this.rewardGrantRepo = rewardGrantRepo;
        this.deviceTokenRepo = deviceTokenRepo;
        this.refreshTokenRepo = refreshTokenRepo;
        this.streakFreezeRepo = streakFreezeRepo;
        this.passwordResetTokenRepo = passwordResetTokenRepo;
        this.friendConnectionRepo = friendConnectionRepo;
        this.mentorMatchRepo = mentorMatchRepo;
        this.mentorshipMessageRepo = mentorshipMessageRepo;
        this.socialPostRepo = socialPostRepo;
        this.emailVerificationCodeRepo = emailVerificationCodeRepo;
        this.emailService = emailService;
    }

    @Transactional
    public void deleteAccount(Long userId) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        List<Habit> habits = habitRepo.findAllByUserAndEntryType(user, HabitEntryType.HABIT);
        List<Habit> allEntries = habitRepo.findAllByUser(user);

        // Compute farewell stats before data is gone
        int totalHabits = habits.size();
        List<HabitCheck> allChecks = habitCheckRepo.findAllByHabitIn(habits);
        int totalDaysTracked = (int) allChecks.stream()
                .map(HabitCheck::getDateKey).distinct().count();
        int bestStreak = (int) allChecks.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        c -> c.getHabit().getId(), java.util.stream.Collectors.counting()))
                .values().stream().mapToLong(Long::longValue).max().orElse(0L);

        String displayName = user.getUsername() != null ? user.getUsername() : user.getEmail();
        String email = user.getEmail();

        // Only send farewell once the transaction has committed successfully.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                emailService.sendAccountDeleted(email, displayName, totalHabits, bestStreak, totalDaysTracked);
            }
        });

        // Delete in FK dependency order. Social / mentorship graph first so nothing
        // still references the user row when we drop it.
        mentorshipMessageRepo.deleteAllByUser(user);
        mentorMatchRepo.deleteAllByMentorOrMentee(user);
        friendConnectionRepo.deleteAllByUser(user);
        socialPostRepo.deleteAllByAuthor(user);
        streakFreezeRepo.deleteAllByUser(user);
        rewardGrantRepo.deleteAllByUser(user);
        for (Habit habit : allEntries) {
            habitCheckRepo.deleteAllByHabit(habit);
        }
        habitRepo.deleteAllByUser(user);
        deviceTokenRepo.deleteAllByUser(user);
        refreshTokenRepo.deleteByUser(user);
        passwordResetTokenRepo.deleteByUser(user);
        emailVerificationCodeRepo.deleteByEmail(email);
        profileRepo.deleteByUser(user);
        userRepo.delete(user);
    }

    /**
     * Sets the user's public username + avatar after first-time Apple
     * sign-in. Validates uniqueness server-side; clients see a 4xx with
     * a recognizable message on collision so the setup screen can
     * surface "username taken" inline.
     *
     * Idempotent — calling again on an already-set-up account just
     * overwrites the chosen username/avatar (which is fine since this
     * endpoint also serves as a "rename" path for users who decide they
     * want a different handle later).
     */
    @Transactional
    public MeResponse setupProfile(Long userId, ProfileSetupRequest req) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        UserProfile profile = profileRepo.findByUser(user)
                .orElseThrow(() -> new IllegalArgumentException("User profile not found"));

        String previousUsername = user.getUsername();
        String requestedUsername = req.username().trim();
        // Allow no-op renames (same username) without tripping the
        // uniqueness check — handy if the client re-submits on retry.
        boolean unchanged = requestedUsername.equalsIgnoreCase(previousUsername);
        if (!unchanged && userRepo.existsByUsername(requestedUsername)) {
            throw new IllegalArgumentException("Username already taken");
        }
        user.setUsername(requestedUsername);
        userRepo.save(user);

        // The display name on the profile is what shows up on leaderboards
        // and friend cards. If it still matches the auto-generated
        // username from createAppleUser (i.e. the *previous* username),
        // sync it to the user's chosen handle so they see their pick
        // everywhere. Custom display names (e.g. a real name set later
        // via the profile API) stay untouched. Compare against the OLD
        // username here — comparing against the just-saved new one would
        // never match on actual renames and the displayName would stay
        // stale forever.
        String currentDisplay = profile.getDisplayName();
        if (currentDisplay == null
                || currentDisplay.isBlank()
                || currentDisplay.equalsIgnoreCase(previousUsername)) {
            profile.setDisplayName(requestedUsername);
        }

        if (req.avatarUrl() != null && !req.avatarUrl().isBlank()) {
            profile.setAvatarUrl(req.avatarUrl().trim());
        }
        profileRepo.save(profile);

        return new MeResponse(user.getId(), user.getEmail(), user.getUsername());
    }

    /**
     * Fast availability probe for the profile-setup screen. Returns true
     * when the username is free OR already owned by the requesting user
     * — i.e. "you can use this without collision."
     */
    @Transactional(readOnly = true)
    public boolean isUsernameAvailable(Long userId, String username) {
        String trimmed = username.trim();
        if (trimmed.isEmpty()) return false;
        User user = userRepo.findById(userId).orElse(null);
        if (user != null && trimmed.equalsIgnoreCase(user.getUsername())) {
            return true;
        }
        return !userRepo.existsByUsername(trimmed);
    }

    @Transactional(readOnly = true)
    public UserPreferencesResponse getPreferences(Long userId) {
        UserProfile profile = requireProfile(userId);
        return new UserPreferencesResponse(profile.isEmailOptIn());
    }

    @Transactional
    public UserPreferencesResponse updatePreferences(Long userId, boolean emailOptIn) {
        UserProfile profile = requireProfile(userId);
        profile.setEmailOptIn(emailOptIn);
        profileRepo.save(profile);
        return new UserPreferencesResponse(profile.isEmailOptIn());
    }

    private UserProfile requireProfile(Long userId) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return profileRepo.findByUser(user)
                .orElseThrow(() -> new IllegalArgumentException("User profile not found"));
    }
}
