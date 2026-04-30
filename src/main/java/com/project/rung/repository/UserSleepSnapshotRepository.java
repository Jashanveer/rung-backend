package com.project.rung.repository;

import com.project.rung.entity.UserSleepSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserSleepSnapshotRepository extends JpaRepository<UserSleepSnapshot, Long> {
    Optional<UserSleepSnapshot> findByUserId(Long userId);
}
