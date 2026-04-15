package com.project.habit_tracker.repository;

import com.project.habit_tracker.entity.MentorMatch;
import com.project.habit_tracker.entity.MentorshipMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MentorshipMessageRepository extends JpaRepository<MentorshipMessage, Long> {
    List<MentorshipMessage> findTop20ByMatchOrderByCreatedAtDesc(MentorMatch match);
}
