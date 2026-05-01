package com.project.rung.repository;

import com.project.rung.entity.UserSleepSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface UserSleepSnapshotRepository extends JpaRepository<UserSleepSnapshot, Long> {
    Optional<UserSleepSnapshot> findByUserId(Long userId);

    /**
     * Hard-delete the snapshot row for a user — used by `UserService.deleteAccount`
     * to clear the FK reference before dropping the parent `User`. Without this
     * the {@code @JoinColumn(name = "user_id")} on {@link UserSleepSnapshot}
     * blocks user deletion with a constraint violation, the whole transaction
     * rolls back, and the account "deletes" without actually deleting.
     */
    @Transactional
    void deleteByUserId(Long userId);
}
