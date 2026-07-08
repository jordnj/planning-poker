package com.planningpoker.web;

import com.planningpoker.dto.Dtos.*;
import com.planningpoker.security.AuthPrincipal;
import com.planningpoker.service.StoryService;
import com.planningpoker.service.VotingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.planningpoker.web.SessionController.principalOf;

@RestController
@RequestMapping("/api/sessions/{code}/stories")
public class StoryController {

    private final StoryService storyService;
    private final VotingService votingService;

    public StoryController(StoryService storyService, VotingService votingService) {
        this.storyService = storyService;
        this.votingService = votingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StoryDto add(@PathVariable String code,
                        @Valid @RequestBody StoryRequest req,
                        Authentication authentication) {
        return storyService.add(code, req, principalOf(authentication));
    }

    @PostMapping("/import")
    @ResponseStatus(HttpStatus.CREATED)
    public List<StoryDto> importStories(@PathVariable String code,
                                        @Valid @RequestBody ImportStoriesRequest req,
                                        Authentication authentication) {
        return storyService.importFromText(code, req.text(), principalOf(authentication));
    }

    @PutMapping("/{storyId}")
    public StoryDto update(@PathVariable String code,
                           @PathVariable UUID storyId,
                           @Valid @RequestBody StoryUpdateRequest req,
                           Authentication authentication) {
        return storyService.update(code, storyId, req, principalOf(authentication));
    }

    @DeleteMapping("/{storyId}")
    public void delete(@PathVariable String code,
                       @PathVariable UUID storyId,
                       Authentication authentication) {
        storyService.delete(code, storyId, principalOf(authentication));
    }

    @PostMapping("/{storyId}/select")
    public void select(@PathVariable String code,
                       @PathVariable UUID storyId,
                       Authentication authentication) {
        votingService.selectStory(code, storyId, principalOf(authentication));
    }

    @PostMapping("/{storyId}/estimate")
    public void estimate(@PathVariable String code,
                         @PathVariable UUID storyId,
                         @Valid @RequestBody EstimateRequest req,
                         Authentication authentication) {
        votingService.estimate(code, storyId, req.value(), principalOf(authentication));
    }
}
