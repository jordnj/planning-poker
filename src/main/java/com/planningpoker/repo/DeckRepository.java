package com.planningpoker.repo;

import com.planningpoker.domain.Deck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeckRepository extends JpaRepository<Deck, UUID> {
    List<Deck> findByBuiltInTrueOrOwnerUserId(UUID ownerUserId);

    List<Deck> findByBuiltInTrue();
}
