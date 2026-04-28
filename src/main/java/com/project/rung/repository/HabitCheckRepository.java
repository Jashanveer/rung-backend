package com.project.rung.repository;

import com.project.rung.entity.Habit;
import com.project.rung.entity.HabitCheck;
import com.project.rung.entity.HabitEntryType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface HabitCheckRepository extends JpaRepository<HabitCheck, Long> {
    List<HabitCheck> findAllByHabitIn(List<Habit> habits);

    Optional<HabitCheck> findByHabitAndDateKey(Habit habit, String dateKey);

    void deleteAllByHabit(Habit habit);

    /// Batch fetch — paired with HabitRepository.findAllByUserIdInAndEntryType
    /// to load every check belonging to a set of users in one round-trip.
    @Query("""
            select hc from HabitCheck hc
            where hc.habit.user.id in :userIds
              and hc.habit.entryType = :entryType
            """)
    List<HabitCheck> findAllByHabitUserIdInAndEntryType(
            @Param("userIds") Collection<Long> userIds,
            @Param("entryType") HabitEntryType entryType
    );
}
