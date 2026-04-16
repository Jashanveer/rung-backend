package com.project.habit_tracker.repository;


import com.project.habit_tracker.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    @Query("""
            select u from User u
            where u.id <> :userId
              and lower(u.email) like lower(concat('%', :query, '%'))
            order by u.id desc
            """)
    List<User> searchByEmail(@Param("userId") Long userId, @Param("query") String query);
}
