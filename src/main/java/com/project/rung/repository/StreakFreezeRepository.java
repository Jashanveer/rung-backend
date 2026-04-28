package com.project.rung.repository;

import com.project.rung.entity.StreakFreeze;
import com.project.rung.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StreakFreezeRepository extends JpaRepository<StreakFreeze, Long> {
    List<StreakFreeze> findAllByUser(User user);

    long countByUserAndUsedAtIsNull(User user);

    void deleteAllByUser(User user);
}
