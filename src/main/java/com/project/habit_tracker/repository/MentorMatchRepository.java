package com.project.habit_tracker.repository;

import com.project.habit_tracker.entity.MentorMatch;
import com.project.habit_tracker.entity.MentorMatchStatus;
import com.project.habit_tracker.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MentorMatchRepository extends JpaRepository<MentorMatch, Long> {
    List<MentorMatch> findAllByMentorAndStatusIn(User mentor, Collection<MentorMatchStatus> statuses);

    List<MentorMatch> findAllByMenteeAndStatusIn(User mentee, Collection<MentorMatchStatus> statuses);

    Optional<MentorMatch> findFirstByMenteeAndStatusInOrderByCreatedAtDesc(User mentee, Collection<MentorMatchStatus> statuses);

    long countByMentorAndStatus(User mentor, MentorMatchStatus status);

    List<MentorMatch> findAllByStatusIn(Collection<MentorMatchStatus> statuses);

    @Modifying
    @Transactional
    @Query("DELETE FROM MentorMatch m WHERE m.mentor = :user OR m.mentee = :user")
    void deleteAllByMentorOrMentee(@Param("user") User user);
}
