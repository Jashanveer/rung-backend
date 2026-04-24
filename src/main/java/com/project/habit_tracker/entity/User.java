package com.project.habit_tracker.entity;

import com.project.habit_tracker.security.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = "email"),
                @UniqueConstraint(columnNames = "username")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, length = 1024)
    private String email;

    /** HMAC-SHA256 of the normalised email — used for DB lookups when email is encrypted. */
    @Column(name = "email_hash", length = 64)
    private String emailHash;

    @Column(length = 40)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    /// Apple's identity-token `sub` claim — stable per-user-per-team id
    /// for accounts that signed up via Sign in with Apple. Null for
    /// password-only accounts. Unique-indexed at the DB level (V14).
    @Column(name = "apple_sub", length = 255)
    private String appleSub;
}
