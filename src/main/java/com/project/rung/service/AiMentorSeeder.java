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

        User aiUser = userRepo.findByEmail(AccountabilityService.AI_MENTOR_EMAIL)
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

        if (aiUser.getEmailHash() == null && emailHash != null) {
            aiUser.setEmailHash(emailHash);
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
