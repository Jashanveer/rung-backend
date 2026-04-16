package com.project.habit_tracker.repository;

import com.project.habit_tracker.entity.DeviceToken;
import com.project.habit_tracker.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {
    List<DeviceToken> findAllByUser(User user);
    Optional<DeviceToken> findByToken(String token);
}
