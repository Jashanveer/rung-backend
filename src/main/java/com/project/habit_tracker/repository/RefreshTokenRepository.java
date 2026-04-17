package com.project.habit_tracker.repository;

import com.project.habit_tracker.entity.RefreshToken;
import com.project.habit_tracker.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    void deleteByUser(User user);

    @Modifying
    @Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :threshold OR t.usedAt IS NOT NULL")
    int deleteExpiredAndUsed(Instant threshold);
}
