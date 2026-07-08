package com.planningpoker.service;

import com.planningpoker.domain.Deck;
import com.planningpoker.dto.Dtos.CreateDeckRequest;
import com.planningpoker.dto.Dtos.DeckDto;
import com.planningpoker.repo.DeckRepository;
import com.planningpoker.security.AuthPrincipal;
import com.planningpoker.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DeckService {

    private final DeckRepository decks;

    public DeckService(DeckRepository decks) {
        this.decks = decks;
    }

    @Transactional(readOnly = true)
    public List<DeckDto> list(AuthPrincipal principal) {
        List<Deck> result = principal != null && principal.isUser()
                ? decks.findByBuiltInTrueOrOwnerUserId(principal.id())
                : decks.findByBuiltInTrue();
        return result.stream().map(DeckDto::of).toList();
    }

    @Transactional
    public DeckDto create(CreateDeckRequest req, AuthPrincipal principal) {
        if (principal == null || !principal.isUser()) {
            throw ApiException.forbidden("Only registered users may save custom decks");
        }
        List<String> values = req.values().stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        if (values.isEmpty()) {
            throw ApiException.badRequest("INVALID_DECK", "Deck needs at least one value");
        }
        Deck deck = new Deck();
        deck.setName(req.name().trim());
        deck.setValues(values);
        deck.setOwnerUserId(principal.id());
        deck.setBuiltIn(false);
        return DeckDto.of(decks.save(deck));
    }
}
