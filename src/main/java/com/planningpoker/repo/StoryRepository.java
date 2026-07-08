package com.planningpoker.repo;

import com.planningpoker.domain.Story;
import com.planningpoker.domain.StoryStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StoryRepository extends JpaRepository<Story, UUID> {
    List<Story> findBySessionIdOrderByOrderIndexAsc(UUID sessionId);

    Optional<Story> findByIdAndSessionId(UUID id, UUID sessionId);

    Optional<Story> findFirstBySessionIdAndStatus(UUID sessionId, StoryStatus status);

    int countBySessionId(UUID sessionId);
}
