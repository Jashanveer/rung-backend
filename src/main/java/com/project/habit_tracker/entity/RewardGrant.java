package com.project.habit_tracker.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One row per (user, habit, dateKey) triple — the idempotency key for check rewards.
 *
 * Unique constraint enforces at-most-once granting regardless of how many times the
 * client calls PUT /habits/{id}/checks/{date}. On revoke (done=false) the row is deleted,
 * so a subsequent re-check can grant again correctly.
 */
@Entity
@Table(
    name = "reward_grants",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_reward_grant_user_habit_date",
        columnNames = {"user_id", "habit_id", "date_key"}
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RewardGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "habit_id")
    private Habit habit;

    @Column(name = "date_key", nullable = false, length = 10)
    private String dateKey;

    @Column(nullable = false)
    private int xpGranted;

    @Column(nullable = false)
    private Instant grantedAt;
}
