package com.project.rung.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Short-lived code used to verify email ownership before account creation.
 */
@Entity
@Table(
        name = "email_verification_codes",
        indexes = @Index(name = "idx_email_verification_email", columnList = "email")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailVerificationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(nullable = false, length = 6)
    private String code;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column
    private Instant usedAt;
}
