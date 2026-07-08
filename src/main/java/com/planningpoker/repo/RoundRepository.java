package com.planningpoker.repo;

import com.planningpoker.domain.Round;
import com.planningpoker.domain.RoundState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoundRepository extends JpaRepository<Round, UUID> {
    Optional<Round> findFirstByStoryIdAndState(UUID storyId, RoundState state);

    Optional<Round> findFirstByStoryIdOrderByCreatedAtDesc(UUID storyId);

    List<Round> findByStoryIdOrderByCreatedAtAsc(UUID storyId);
}
