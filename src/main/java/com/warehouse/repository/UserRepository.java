package com.warehouse.repository;

import com.warehouse.entity.Role;
import com.warehouse.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    boolean existsByUsername(String username);

    Optional<User> findByUsername(String username);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
                select u
                from User u
                where u.role = :role
                  and u.active = true
            """)
    List<User> findActiveUsersByRoleForUpdate(@Param("role") Role role);
}
