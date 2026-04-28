package com.project.rung.repository;

import com.project.rung.entity.MentorMatch;
import com.project.rung.entity.MentorshipMessage;
import com.project.rung.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface MentorshipMessageRepository extends JpaRepository<MentorshipMessage, Long> {
    List<MentorshipMessage> findTop20ByMatchOrderByCreatedAtDesc(MentorMatch match);

    boolean existsByMatchAndSenderAndCreatedAtAfter(MentorMatch match, User sender, Instant after);

    @Modifying
    @Transactional
    @Query("DELETE FROM MentorshipMessage m WHERE m.match.mentor = :user OR m.match.mentee = :user OR m.sender = :user")
    void deleteAllByUser(@Param("user") User user);
}
