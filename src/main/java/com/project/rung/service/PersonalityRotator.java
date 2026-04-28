package com.project.rung.service;

import com.project.rung.entity.UserProfile;
import com.project.rung.repository.UserProfileRepository;
import com.project.rung.service.MentorAI.MentorPersonality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Owns the "AI mentor has weekly moods" behaviour. Each user is assigned one
 * of the four {@link MentorPersonality} values; after 7 days, the next AI
 * call lazily rotates to a fresh random one (avoiding an immediate repeat
 * so the mentor doesn't feel stuck in the same voice two weeks running).
 *
 * Lazy evaluation — no separate scheduled job. This means a dormant user
 * who returns after months gets a fresh personality the moment the next AI
 * call fires, which matches the "AI mood" mental model.
 */
@Service
public class PersonalityRotator {

    private static final Logger log = LoggerFactory.getLogger(PersonalityRotator.class);
    private static final Duration ROTATION_PERIOD = Duration.ofDays(7);

    private final UserProfileRepository profileRepo;

    public PersonalityRotator(UserProfileRepository profileRepo) {
        this.profileRepo = profileRepo;
    }

    /// Returns the personality the mentor should use for this user right
    /// now. Persists an updated value when rotation fires. Always returns
    /// a non-null value even for brand-new users.
    @Transactional
    public MentorPersonality currentPersonalityFor(UserProfile profile) {
        MentorPersonality current = parse(profile.getMentorPersonality());
        Instant assignedAt = profile.getMentorPersonalityAssignedAt();
        boolean needsRoll = current == null
                || assignedAt == null
                || Duration.between(assignedAt, Instant.now()).compareTo(ROTATION_PERIOD) >= 0;

        if (!needsRoll) return current;

        MentorPersonality next = pickRandomDifferentFrom(current);
        profile.setMentorPersonality(next.name());
        profile.setMentorPersonalityAssignedAt(Instant.now());
        profileRepo.save(profile);
        log.debug("Rotated mentor personality for user {} from {} to {}",
                profile.getUser().getId(), current, next);
        return next;
    }

    private static MentorPersonality parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return MentorPersonality.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static MentorPersonality pickRandomDifferentFrom(MentorPersonality previous) {
        List<MentorPersonality> pool = new ArrayList<>(List.of(MentorPersonality.values()));
        if (previous != null && pool.size() > 1) {
            pool.remove(previous);
        }
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }
}
