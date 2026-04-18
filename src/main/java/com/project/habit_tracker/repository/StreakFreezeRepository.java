package com.project.habit_tracker.repository;

import com.project.habit_tracker.entity.StreakFreeze;
import com.project.habit_tracker.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StreakFreezeRepository extends JpaRepository<StreakFreeze, Long> {
    List<StreakFreeze> findAllByUser(User user);

    long countByUserAndUsedAtIsNull(User user);

    void deleteAllByUser(User user);
}
