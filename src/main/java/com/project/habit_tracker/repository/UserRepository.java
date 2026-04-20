package com.project.habit_tracker.repository;


import com.project.habit_tracker.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    Optional<User> findByEmailHash(String emailHash);

    Optional<User> findByUsername(String username);

    boolean existsByEmail(String email);

    boolean existsByEmailHash(String emailHash);

    boolean existsByUsername(String username);

    @Query("""
            select u from User u
            where u.id <> :userId
              and lower(u.username) like lower(concat('%', :query, '%'))
            order by u.id desc
            """)
    List<User> searchByIdentity(@Param("userId") Long userId, @Param("query") String query);
}
