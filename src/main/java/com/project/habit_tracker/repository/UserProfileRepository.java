package com.project.habit_tracker.repository;

import com.project.habit_tracker.entity.User;
import com.project.habit_tracker.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {
    Optional<UserProfile> findByUser(User user);

    void deleteByUser(User user);
}
