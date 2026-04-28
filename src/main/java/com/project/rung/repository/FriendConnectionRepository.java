package com.project.rung.repository;

import com.project.rung.entity.FriendConnection;
import com.project.rung.entity.FriendConnectionStatus;
import com.project.rung.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FriendConnectionRepository extends JpaRepository<FriendConnection, Long> {

    @Modifying
    @Transactional
    @Query("DELETE FROM FriendConnection fc WHERE fc.requester = :user OR fc.addressee = :user")
    void deleteAllByUser(@Param("user") User user);

    @Query("""
            select fc from FriendConnection fc
            where fc.status = :status
              and (fc.requester = :user or fc.addressee = :user)
            order by fc.updatedAt desc
            """)
    List<FriendConnection> findAllByUserAndStatus(
            @Param("user") User user,
            @Param("status") FriendConnectionStatus status
    );

    Optional<FriendConnection> findByRequesterAndAddressee(User requester, User addressee);

    List<FriendConnection> findAllByRequesterAndStatus(User requester, FriendConnectionStatus status);

    List<FriendConnection> findAllByAddresseeAndStatus(User addressee, FriendConnectionStatus status);

    @Query("""
            select fc from FriendConnection fc
            where (fc.requester = :left and fc.addressee = :right)
               or (fc.requester = :right and fc.addressee = :left)
            """)
    Optional<FriendConnection> findBetween(@Param("left") User left, @Param("right") User right);

    @Query("""
            select fc from FriendConnection fc
            where fc.status in :statuses
              and (fc.requester = :user or fc.addressee = :user)
            """)
    List<FriendConnection> findAllByUserAndStatusIn(
            @Param("user") User user,
            @Param("statuses") Collection<FriendConnectionStatus> statuses
    );
}
