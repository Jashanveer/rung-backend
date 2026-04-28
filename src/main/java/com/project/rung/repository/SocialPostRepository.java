package com.project.rung.repository;

import com.project.rung.entity.SocialPost;
import com.project.rung.entity.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SocialPostRepository extends JpaRepository<SocialPost, Long> {
    List<SocialPost> findAllByOrderByCreatedAtDesc(Pageable pageable);

    void deleteAllByAuthor(User author);
}
