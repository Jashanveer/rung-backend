package com.project.habit_tracker.entity;

import jakarta.persistence.*;
import lombok.*;

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
}