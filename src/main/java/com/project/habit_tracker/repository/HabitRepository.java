package com.project.habit_tracker.repository;

import com.project.habit_tracker.entity.Habit;
import com.project.habit_tracker.entity.HabitEntryType;
import com.project.habit_tracker.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HabitRepository extends JpaRepository<Habit, Long> {
    List<Habit> findAllByUser(User user);
    List<Habit> findAllByUserAndEntryType(User user, HabitEntryType entryType);

    Optional<Habit> findByIdAndUser(Long id, User user);
    Optional<Habit> findByIdAndUserAndEntryType(Long id, User user, HabitEntryType entryType);

    void deleteAllByUser(User user);
    void deleteAllByUserAndEntryType(User user, HabitEntryType entryType);
}
