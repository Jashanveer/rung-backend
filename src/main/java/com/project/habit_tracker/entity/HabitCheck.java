package com.project.habit_tracker.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
        name = "habit_checks",
        uniqueConstraints = @UniqueConstraint(columnNames = {"habit_id", "date_key"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HabitCheck {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "habit_id")
    private Habit habit;

    // Store exactly "YYYY-MM-DD" so it matches your UI
    @Column(name = "date_key", nullable = false, length = 10)
    private String dateKey;

    @Column(nullable = false)
    private boolean done;

    // Nullable — historical rows won't have it
    @Column(name = "completed_at")
    private Instant completedAt;

    /// Tier awarded for this specific check — captured at the moment the
    /// client reported it so later tier changes to the parent Habit don't
    /// retroactively adjust historical scoring. Null on legacy rows.
    @Column(name = "verification_tier", length = 16)
    private String verificationTier;

    /// Signal source that corroborated this check (or `selfReport` when no
    /// external signal was available). Nullable to preserve back-compat
    /// with legacy rows that predate the verification stack.
    @Column(name = "verification_source", length = 32)
    private String verificationSource;
}