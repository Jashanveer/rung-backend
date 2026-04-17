package com.project.habit_tracker.repository;

import com.project.habit_tracker.entity.EmailVerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, Long> {

    Optional<EmailVerificationCode> findTopByEmailOrderByIdDesc(String email);

    @Modifying
    @Transactional
    @Query("DELETE FROM EmailVerificationCode c WHERE c.email = :email")
    void deleteByEmail(@Param("email") String email);

    @Modifying
    @Transactional
    @Query("DELETE FROM EmailVerificationCode c WHERE c.expiresAt < :now")
    void deleteExpiredBefore(@Param("now") Instant now);
}
