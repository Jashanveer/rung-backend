package com.project.rung.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "streak_freezes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StreakFreeze {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private Instant grantedAt;

    @Column(nullable = true)
    private Instant usedAt;

    @Column(nullable = true, length = 10)
    private String usedForDateKey;
}
