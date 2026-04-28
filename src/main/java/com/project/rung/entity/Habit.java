package com.project.rung.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "habits")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Habit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private String title;

    @Column(name = "reminder_window", length = 24)
    private String reminderWindow;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 16)
    private HabitEntryType entryType = HabitEntryType.HABIT;

    /// Stable id into the client's `CanonicalHabits` registry (e.g. `"run"`,
    /// `"workout"`). Nil means the user's title didn't match any canonical.
    @Column(name = "canonical_key", length = 32)
    private String canonicalKey;

    /// One of `auto` / `partial` / `selfReport`. Stored as lowercase to match
    /// the Swift `VerificationTier` raw values so the field round-trips
    /// without per-platform translation tables.
    @Column(name = "verification_tier", length = 16)
    private String verificationTier;

    /// Swift `VerificationSource` raw value, or null for honor-system
    /// habits. Persisted as the same camelCase string the client stores.
    @Column(name = "verification_source", length = 32)
    private String verificationSource;

    /// Threshold / activity-type code for the verification query. Semantics
    /// match `verification_source` on the client — e.g. step threshold for
    /// `healthKitSteps`, workout type raw value for `healthKitWorkout`.
    @Column(name = "verification_param")
    private Double verificationParam;

    /// Target completions per ISO week for frequency-based habits
    /// ("gym 5×/week"). Null = legacy daily cadence.
    @Column(name = "weekly_target")
    private Integer weeklyTarget;
}
