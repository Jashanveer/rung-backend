package com.project.habit_tracker.entity;

import com.project.habit_tracker.security.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "user_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", unique = true)
    private User user;

    @Column(nullable = false)
    private String displayName;

    @Column(length = 512)
    private String avatarUrl;

    @Column(nullable = false)
    private String timezone;

    @Column(nullable = false, length = 16)
    private String language;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false)
    private String goals;

    @Builder.Default
    @Column(nullable = false, columnDefinition = "integer default 50")
    private int mentorRating = 50;
}
