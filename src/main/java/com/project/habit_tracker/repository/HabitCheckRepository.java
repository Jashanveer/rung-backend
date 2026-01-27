package com.project.habit_tracker.repository;

import com.project.habit_tracker.entity.Habit;
import com.project.habit_tracker.entity.HabitCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HabitCheckRepository extends JpaRepository<HabitCheck, Long> {
    List<HabitCheck> findAllByHabitIn(List<Habit> habits);

    Optional<HabitCheck> findByHabitAndDateKey(Habit habit, String dateKey);

    void deleteAllByHabit(Habit habit);
}
