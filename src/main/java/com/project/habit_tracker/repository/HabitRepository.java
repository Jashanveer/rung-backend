package com.project.habit_tracker.repository;

import com.project.habit_tracker.entity.Habit;
import com.project.habit_tracker.entity.HabitEntryType;
import com.project.habit_tracker.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface HabitRepository extends JpaRepository<Habit, Long> {
    List<Habit> findAllByUser(User user);
    List<Habit> findAllByUserAndEntryType(User user, HabitEntryType entryType);

    Optional<Habit> findByIdAndUser(Long id, User user);
    Optional<Habit> findByIdAndUserAndEntryType(Long id, User user, HabitEntryType entryType);

    void deleteAllByUser(User user);
    void deleteAllByUserAndEntryType(User user, HabitEntryType entryType);

    /// Batch fetch — used by AccountabilityService to populate stats for the
    /// viewer plus every related user (mentees, follows, suggestions) in a
    /// single query instead of N round-trips.
    @Query("select h from Habit h where h.user.id in :userIds and h.entryType = :entryType")
    List<Habit> findAllByUserIdInAndEntryType(
            @Param("userIds") Collection<Long> userIds,
            @Param("entryType") HabitEntryType entryType
    );
}
