package com.project.habit_tracker.repository;

import com.project.habit_tracker.entity.SocialPost;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SocialPostRepository extends JpaRepository<SocialPost, Long> {
    List<SocialPost> findTop25ByOrderByCreatedAtDesc();
}
