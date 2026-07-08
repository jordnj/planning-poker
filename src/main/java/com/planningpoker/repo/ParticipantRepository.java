package com.planningpoker.repo;

import com.planningpoker.domain.Participant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ParticipantRepository extends JpaRepository<Participant, UUID> {
    List<Participant> findBySessionIdAndActiveTrueOrderByJoinedAtAsc(UUID sessionId);

    Optional<Participant> findByIdAndSessionId(UUID id, UUID sessionId);

    boolean existsByIdAndActiveTrueAndSession_Code(UUID id, String sessionCode);
}
