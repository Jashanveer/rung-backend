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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    /// Avatar URLs are stored verbatim and rendered on every dashboard /
    /// leaderboard / friend card any other user sees, so an attacker who can
    /// land a self-controlled URL here gets a stored XSS / tracking-pixel
    /// vector against everyone in their social graph. Mirror AuthService's
    /// allowlist: avatars must come from our DiceBear endpoint.
    private static final String AVATAR_BASE_URL = "https://api.dicebear.com/9.x/adventurer/png?seed=";

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
    private final AccountabilityStreamService streamService;

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
            EmailService emailService,
            AccountabilityStreamService streamService
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
        this.streamService = streamService;
    }

    /// Mirror of HabitService.broadcastHabitsChanged but for non-habit
    /// data (preferences, profile name/avatar). Emits a `prefs.changed`
    /// SSE event after the transaction commits so other devices the
    /// same user has open can refresh their settings + dashboard cache
    /// without waiting for the polling tick.
    private void broadcastPrefsChanged(Long userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            streamService.publishToUser(userId, "prefs.changed",
                                    Map.of("at", Instant.now().toString()));
                        }
                    }
            );
        } else {
            streamService.publishToUser(userId, "prefs.changed",
                    Map.of("at", Instant.now().toString()));
        }
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

        String username = user.getUsername();
        String displayName = username != null ? username : user.getEmail();
        String email = user.getEmail();

        log.info("deleteAccount start userId={} username={} habits={} entries={}",
                userId, username, totalHabits, allEntries.size());

        // Only send farewell once the transaction has committed successfully.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.info("deleteAccount committed userId={} username={} — username freed", userId, username);
                emailService.sendAccountDeleted(email, displayName, totalHabits, bestStreak, totalDaysTracked);
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    log.error("deleteAccount transaction did NOT commit userId={} username={} status={} — row + username remain in DB; check upstream FK constraints",
                            userId, username, status);
                }
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
        // Force the DELETEs to flush to the DB inside the transaction so any
        // FK violation surfaces here (and rolls the whole thing back) rather
        // than at commit time, where the only signal would be a silent
        // afterCompletion(rollback). With explicit flush, the controller sees
        // a 409 from GlobalExceptionHandler.handleDataIntegrity and the
        // client surfaces a real error instead of a fake "deleted" toast.
        userRepo.flush();
        log.info("deleteAccount flushed userId={} username={} — pending commit", userId, username);
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

        // The display name on the profile is what shows up on
        // leaderboards and friend cards. Three layered rules, in
        // priority order:
        //
        //  1. If the client supplied a `displayName`, that always wins —
        //     this is the post-Apple-signup path where the user typed
        //     their real name on the setup screen because Apple didn't
        //     return one (private-relay sign-ins drop fullName after
        //     the first authorization, so the previous behaviour left
        //     them stuck with the random hash username as their public
        //     name).
        //
        //  2. Otherwise, if the existing display name still matches the
        //     auto-generated username from createAppleUser (i.e. the
        //     *previous* username), sync it to the user's chosen
        //     handle so they see their pick everywhere. Compare
        //     against the OLD username — the new one would never match
        //     on actual renames and the displayName would stay stale
        //     forever.
        //
        //  3. Otherwise leave the existing display name untouched
        //     (custom name set earlier).
        String requestedDisplay = req.displayName() == null ? null : req.displayName().trim();
        String currentDisplay = profile.getDisplayName();
        if (requestedDisplay != null && !requestedDisplay.isEmpty()) {
            profile.setDisplayName(requestedDisplay);
        } else if (currentDisplay == null
                || currentDisplay.isBlank()
                || currentDisplay.equalsIgnoreCase(previousUsername)) {
            profile.setDisplayName(requestedUsername);
        }

        if (req.avatarUrl() != null && !req.avatarUrl().isBlank()) {
            String trimmedAvatar = req.avatarUrl().trim();
            // Reject avatars that aren't a DiceBear URL we generate. Without
            // this check, a fresh Apple sign-up could POST any URL here and
            // it would render on every other user who sees them in social.
            if (!trimmedAvatar.startsWith(AVATAR_BASE_URL)) {
                throw new IllegalArgumentException("Choose one of the predefined avatars");
            }
            profile.setAvatarUrl(trimmedAvatar);
        }
        profileRepo.save(profile);

        broadcastPrefsChanged(userId);
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
        broadcastPrefsChanged(userId);
        return new UserPreferencesResponse(profile.isEmailOptIn());
    }

    private UserProfile requireProfile(Long userId) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return profileRepo.findByUser(user)
                .orElseThrow(() -> new IllegalArgumentException("User profile not found"));
    }
}
