package com.project.habit_tracker.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "mentor_matches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MentorMatch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "mentor_id")
    private User mentor;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "mentee_id")
    private User mentee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MentorMatchStatus status;

    @Column(nullable = false)
    private int matchScore;

    @Column(nullable = false)
    private String matchReasons;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant endedAt;
}
