package com.planningpoker.repo;

import com.planningpoker.domain.Session;
import com.planningpoker.domain.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository extends JpaRepository<Session, UUID> {
    Optional<Session> findByCode(String code);

    boolean existsByCode(String code);

    List<Session> findByStatusAndLastActivityAtBefore(SessionStatus status, Instant cutoff);
}
