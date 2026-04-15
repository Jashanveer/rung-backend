package com.project.habit_tracker.repository;

import com.project.habit_tracker.entity.MentorMatch;
import com.project.habit_tracker.entity.MentorMatchStatus;
import com.project.habit_tracker.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MentorMatchRepository extends JpaRepository<MentorMatch, Long> {
    List<MentorMatch> findAllByMentorAndStatusIn(User mentor, Collection<MentorMatchStatus> statuses);

    List<MentorMatch> findAllByMenteeAndStatusIn(User mentee, Collection<MentorMatchStatus> statuses);

    Optional<MentorMatch> findFirstByMenteeAndStatusInOrderByCreatedAtDesc(User mentee, Collection<MentorMatchStatus> statuses);

    long countByMentorAndStatus(User mentor, MentorMatchStatus status);
}
