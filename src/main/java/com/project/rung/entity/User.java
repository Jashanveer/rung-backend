package com.project.rung.entity;

import com.project.rung.security.EncryptedStringConverter;
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

    /// True once the user has successfully POSTed `/api/users/me/setup-profile`.
    /// False for fresh Apple sign-ups whose row was provisioned with an
    /// auto-generated placeholder username (see `AuthService.createAppleUser`)
    /// — the client reads this on cold launch and re-shows the
    /// `AppleProfileSetupView` until the user finishes. Password
    /// registrations set this true at creation time because they pick
    /// their own username during sign-up. (V15.)
    @Column(name = "profile_setup_completed", nullable = false)
    private boolean profileSetupCompleted;
}
