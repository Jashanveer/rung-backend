package com.project.rung.repository;


import com.project.rung.entity.User;
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

    Optional<User> findByAppleSub(String appleSub);

    @Query("""
            select u from User u
            where u.id <> :userId
              and lower(u.username) like lower(concat('%', :query, '%'))
            order by u.id desc
            """)
    List<User> searchByIdentity(@Param("userId") Long userId, @Param("query") String query);

    /// Returns every user that should be visible in social/mentor lookups —
    /// excludes the viewer plus the seeded AI mentor account so callers
    /// don't have to filter in memory after a full table scan.
    @Query("""
            select u from User u
            where u.id <> :viewerId
              and (u.username is null or u.username <> :aiUsername)
            """)
    List<User> findAllSocialCandidates(
            @Param("viewerId") Long viewerId,
            @Param("aiUsername") String aiUsername
    );
}
