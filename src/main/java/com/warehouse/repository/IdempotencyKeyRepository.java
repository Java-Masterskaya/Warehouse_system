package com.warehouse.repository;

import com.warehouse.entity.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    @Query("SELECT ik FROM IdempotencyKey ik "
            + "JOIN FETCH ik.user "
            + "LEFT JOIN FETCH ik.movement "
            + "WHERE ik.keyHash = :keyHash AND ik.user.id = :userId AND ik.endpoint = :endpoint")
    Optional<IdempotencyKey> findByKeyHashAndUserIdAndEndpoint(
            @Param("keyHash") String keyHash,
            @Param("userId") Long userId,
            @Param("endpoint") String endpoint
    );

    @Modifying
    @Transactional
    @Query("DELETE FROM IdempotencyKey ik WHERE ik.expiresAt < :cutoff")
    int deleteExpiredKeys(@Param("cutoff") LocalDateTime cutoff);
}