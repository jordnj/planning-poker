package com.planningpoker.repo;

import com.planningpoker.domain.Vote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VoteRepository extends JpaRepository<Vote, UUID> {
    List<Vote> findByRoundId(UUID roundId);

    Optional<Vote> findByRoundIdAndParticipantId(UUID roundId, UUID participantId);

    long countByRoundId(UUID roundId);

    void deleteByRoundId(UUID roundId);
}
