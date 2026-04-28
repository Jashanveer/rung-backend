package com.project.rung.entity;

import com.project.rung.security.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "user_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", unique = true)
    private User user;

    @Column(nullable = false)
    private String displayName;

    @Column(length = 512)
    private String avatarUrl;

    @Column(nullable = false)
    private String timezone;

    @Column(nullable = false, length = 16)
    private String language;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, length = 1024)
    private String goals;

    @Builder.Default
    @Column(nullable = false, columnDefinition = "integer default 50")
    private int mentorRating = 50;

    @Builder.Default
    @Column(name = "email_opt_in", nullable = false, columnDefinition = "boolean default true")
    private boolean emailOptIn = true;

    /// Current AI mentor personality for this user. Rotates every 7 days
    /// via PersonalityRotator (lazy — checked on each AI call). Nullable:
    /// first AI call assigns the initial value.
    @Column(name = "mentor_personality", length = 24)
    private String mentorPersonality;

    /// Timestamp the current personality was assigned. When more than 7 days
    /// old, PersonalityRotator rolls a new random personality (avoiding
    /// immediate repeat) and updates this field.
    @Column(name = "mentor_personality_assigned_at")
    private Instant mentorPersonalityAssignedAt;

    /// ~100-word third-person memory note about this user, maintained by
    /// the AI mentor itself (distilled after chat sessions). Injected back
    /// into every future system prompt so the mentor "remembers" patterns
    /// without fine-tuning. Nullable / blank for brand-new users.
    @Column(name = "mentor_memory", columnDefinition = "TEXT")
    private String mentorMemory;
}
