package com.project.habit_tracker.repository;

import com.project.habit_tracker.entity.Habit;
import com.project.habit_tracker.entity.RewardGrant;
import com.project.habit_tracker.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RewardGrantRepository extends JpaRepository<RewardGrant, Long> {

    List<RewardGrant> findAllByUser(User user);

    Optional<RewardGrant> findByUserAndHabitAndDateKey(User user, Habit habit, String dateKey);

    void deleteByUserAndHabitAndDateKey(User user, Habit habit, String dateKey);

    void deleteByHabit(Habit habit);

    @Query("SELECT COUNT(g) FROM RewardGrant g WHERE g.user = :user AND g.dateKey = :dateKey")
    long countByUserAndDateKey(@Param("user") User user, @Param("dateKey") String dateKey);

    @Query("SELECT COALESCE(SUM(g.xpGranted), 0) FROM RewardGrant g WHERE g.user = :user")
    int sumXpByUser(@Param("user") User user);
}
