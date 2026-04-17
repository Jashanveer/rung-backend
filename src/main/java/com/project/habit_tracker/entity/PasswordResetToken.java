package com.project.habit_tracker.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Single-use token for the forgot-password flow.
 * Expires 24 hours after creation. Marked used when the password is reset.
 */
@Entity
@Table(name = "password_reset_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(nullable = false)
    private Instant expiresAt;

    /** Set when the token is consumed; non-null tokens are invalid. */
    @Column
    private Instant usedAt;
}
