package com.project.rung.service;

import com.project.rung.entity.User;
import com.project.rung.entity.UserProfile;
import com.project.rung.repository.UserProfileRepository;
import com.project.rung.repository.UserRepository;
import com.project.rung.security.EncryptedStringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guarantees the AI mentor user exists on startup — independent of Flyway.
 *
 * The V7 and V9 Flyway migrations seed the AI mentor, but local dev disables
 * Flyway and lets Hibernate manage the schema, which skips those seeds. This
 * runner is idempotent and runs in every environment so the AI mentor is
 * always present and always named "Bruce".
 */
@Component
public class AiMentorSeeder {

    private static final Logger log = LoggerFactory.getLogger(AiMentorSeeder.class);
    private static final String BRUCE_DISPLAY_NAME = "Bruce";

    private final UserRepository userRepo;
    private final UserProfileRepository profileRepo;
    private final EncryptedStringConverter encryptedStringConverter;

    public AiMentorSeeder(
            UserRepository userRepo,
            UserProfileRepository profileRepo,
            EncryptedStringConverter encryptedStringConverter
    ) {
        this.userRepo = userRepo;
        this.profileRepo = profileRepo;
        this.encryptedStringConverter = encryptedStringConverter;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedAiMentor() {
        String emailHash = encryptedStringConverter.hash(AccountabilityService.AI_MENTOR_EMAIL);

        // Username fallback covers the case where V7 inserted the AI mentor
        // with a plaintext email, but APP_ENCRYPTION_KEY is set so the JPA
        // converter encrypts our lookup value and findByEmail / findByEmailHash
        // both miss. Username is unencrypted and uniquely indexed, so it is
        // the most reliable handle on the V7-seeded row.
        User aiUser = userRepo.findByUsername(AccountabilityService.AI_MENTOR_USERNAME)
                .or(() -> userRepo.findByEmail(AccountabilityService.AI_MENTOR_EMAIL))
                .or(() -> userRepo.findByEmailHash(emailHash))
                .orElseGet(() -> {
                    User created = userRepo.save(User.builder()
                            .email(AccountabilityService.AI_MENTOR_EMAIL)
                            .emailHash(emailHash)
                            .username(AccountabilityService.AI_MENTOR_USERNAME)
                            // Sentinel hash that never matches BCrypt output, so the account
                            // cannot be logged into.
                            .passwordHash("!!AI_MENTOR_NO_LOGIN!!")
                            // System bot — not a real user; treat setup as
                            // done so it never trips a
                            // SUM(profile_setup_completed=false) dashboard.
                            .profileSetupCompleted(true)
                            .build());
                    log.info("Seeded AI mentor user id={}", created.getId());
                    return created;
                });

        // Sync the canonical email + hash on every boot. The V7 migration
        // seeded this user with `ai-mentor@forma.app` before the rebrand;
        // V9 only renamed the display name, so legacy databases still have
        // the old email encrypted in place. AccountabilityService.aiMentorUser()
        // looks up by `findByEmail(AI_MENTOR_EMAIL)` — if the column doesn't
        // match, no AI mentor match is ever created, dashboard.match stays
        // null, and the iOS chat send silently fails. Realigning the email
        // here unblocks every account that landed before this fix.
        boolean dirty = false;
        if (!AccountabilityService.AI_MENTOR_EMAIL.equals(aiUser.getEmail())) {
            log.info("Realigning AI mentor email from '{}' to '{}'",
                    aiUser.getEmail(), AccountabilityService.AI_MENTOR_EMAIL);
            aiUser.setEmail(AccountabilityService.AI_MENTOR_EMAIL);
            dirty = true;
        }
        if (emailHash != null && !emailHash.equals(aiUser.getEmailHash())) {
            aiUser.setEmailHash(emailHash);
            dirty = true;
        }
        if (dirty) {
            userRepo.save(aiUser);
        }

        UserProfile profile = profileRepo.findByUser(aiUser).orElseGet(() -> {
            UserProfile created = profileRepo.save(UserProfile.builder()
                    .user(aiUser)
                    .displayName(BRUCE_DISPLAY_NAME)
                    .avatarUrl(null)
                    .timezone("UTC")
                    .language("EN")
                    .goals("Personalised tips, daily check-ins, motivation")
                    .mentorRating(100)
                    .build());
            log.info("Seeded AI mentor profile id={}", created.getId());
            return created;
        });

        if (!BRUCE_DISPLAY_NAME.equals(profile.getDisplayName())) {
            log.info("Renaming AI mentor profile from '{}' to '{}'", profile.getDisplayName(), BRUCE_DISPLAY_NAME);
            profile.setDisplayName(BRUCE_DISPLAY_NAME);
            profileRepo.save(profile);
        }
    }
}
