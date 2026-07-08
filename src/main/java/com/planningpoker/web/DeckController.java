package com.planningpoker.web;

import com.planningpoker.dto.Dtos.CreateDeckRequest;
import com.planningpoker.dto.Dtos.DeckDto;
import com.planningpoker.service.DeckService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.planningpoker.web.SessionController.principalOf;

@RestController
@RequestMapping("/api/decks")
public class DeckController {

    private final DeckService deckService;

    public DeckController(DeckService deckService) {
        this.deckService = deckService;
    }

    @GetMapping
    public List<DeckDto> list(Authentication authentication) {
        return deckService.list(principalOf(authentication));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeckDto create(@Valid @RequestBody CreateDeckRequest req, Authentication authentication) {
        return deckService.create(req, principalOf(authentication));
    }
}
