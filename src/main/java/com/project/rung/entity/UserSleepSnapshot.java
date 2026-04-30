package com.project.rung.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Per-user sleep snapshot uploaded by the iOS client. The Mac client
 * reads this row instead of querying HealthKit locally — HK isn't
 * available on native macOS apps, so the Mac borrows the iPhone's view
 * of the user's sleep instead.
 *
 * One row per user, overwritten on every iOS upload. We keep `updatedAt`
 * so the Mac can warn the user when the data is stale ("last synced 3
 * days ago — open Rung on iPhone").
 */
@Entity
@Table(name = "user_sleep_snapshots")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSleepSnapshot {
    /// Shares the primary-key column with the user row. We don't need a
    /// surrogate id because there's at most one snapshot per user.
    @Id
    @Column(name = "user_id")
    private Long userId;

    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "sample_count", nullable = false)
    private int sampleCount;

    @Column(name = "median_wake_minutes", nullable = false)
    private int medianWakeMinutes;

    @Column(name = "median_bed_minutes", nullable = false)
    private int medianBedMinutes;

    @Column(name = "average_duration_hours", nullable = false)
    private double averageDurationHours;

    @Column(name = "sleep_debt_hours", nullable = false)
    private double sleepDebtHours;

    /// Nullable until ≥ 14 nights tracked.
    @Column(name = "median_sleep_midpoint_minutes")
    private Integer medianSleepMidpointMinutes;

    @Column(name = "midpoint_iqr_minutes", nullable = false)
    private int midpointIqrMinutes;

    @Column(name = "chronotype_stable", nullable = false)
    private boolean chronotypeStable;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
