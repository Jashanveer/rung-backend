package com.project.habit_tracker.repository;

import com.project.habit_tracker.entity.User;
import com.project.habit_tracker.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {
    Optional<UserProfile> findByUser(User user);

    void deleteByUser(User user);

    @Query("select p from UserProfile p where p.user.id in :userIds")
    List<UserProfile> findAllByUserIdIn(@Param("userIds") Collection<Long> userIds);
}
