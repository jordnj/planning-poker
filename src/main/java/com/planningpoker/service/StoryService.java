package com.planningpoker.service;

import com.planningpoker.domain.*;
import com.planningpoker.dto.Dtos.*;
import com.planningpoker.repo.RoundRepository;
import com.planningpoker.repo.StoryRepository;
import com.planningpoker.security.AuthPrincipal;
import com.planningpoker.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class StoryService {

    private final SessionService sessionService;
    private final StoryRepository stories;
    private final RoundRepository rounds;
    private final com.planningpoker.repo.VoteRepository votes;
    private final Broadcaster broadcaster;

    public StoryService(SessionService sessionService, StoryRepository stories,
                        RoundRepository rounds, com.planningpoker.repo.VoteRepository votes,
                        Broadcaster broadcaster) {
        this.sessionService = sessionService;
        this.stories = stories;
        this.rounds = rounds;
        this.votes = votes;
        this.broadcaster = broadcaster;
    }

    @Transactional
    public StoryDto add(String code, StoryRequest req, AuthPrincipal principal) {
        Session session = sessionService.requireActiveSession(code);
        sessionService.requireFacilitator(session, principal);
        Story story = newStory(session, req.title(), req.description(), req.referenceUrl());
        stories.save(story);
        session.touch();
        broadcastStories(session);
        return StoryDto.of(story);
    }

    /** FR-12: paste-import, one story title per line. */
    @Transactional
    public List<StoryDto> importFromText(String code, String text, AuthPrincipal principal) {
        Session session = sessionService.requireActiveSession(code);
        sessionService.requireFacilitator(session, principal);
        List<StoryDto> created = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String title = line.trim();
            if (title.isEmpty()) {
                continue;
            }
            Story story = newStory(session, title.length() > 255 ? title.substring(0, 255) : title, null, null);
            stories.save(story);
            created.add(StoryDto.of(story));
        }
        if (created.isEmpty()) {
            throw ApiException.badRequest("EMPTY_IMPORT", "No story titles found in the pasted text");
        }
        session.touch();
        broadcastStories(session);
        return created;
    }

    @Transactional
    public StoryDto update(String code, UUID storyId, StoryUpdateRequest req, AuthPrincipal principal) {
        Session session = sessionService.requireActiveSession(code);
        sessionService.requireFacilitator(session, principal);
        Story story = requireStory(session, storyId);
        if (req.title() != null && !req.title().isBlank()) {
            story.setTitle(req.title().trim());
        }
        if (req.description() != null) {
            story.setDescription(req.description().isBlank() ? null : req.description());
        }
        if (req.referenceUrl() != null) {
            story.setReferenceUrl(req.referenceUrl().isBlank() ? null : req.referenceUrl().trim());
        }
        if (req.orderIndex() != null) {
            reorder(session, story, req.orderIndex());
        }
        session.touch();
        broadcastStories(session);
        return StoryDto.of(story);
    }

    @Transactional
    public void delete(String code, UUID storyId, AuthPrincipal principal) {
        Session session = sessionService.requireActiveSession(code);
        sessionService.requireFacilitator(session, principal);
        Story story = requireStory(session, storyId);
        if (story.getStatus() == StoryStatus.VOTING) {
            throw ApiException.conflict("STORY_ACTIVE", "Cannot delete the story currently being voted on");
        }
        rounds.findByStoryIdOrderByCreatedAtAsc(story.getId()).forEach(round -> {
            votes.deleteByRoundId(round.getId());
            rounds.delete(round);
        });
        stories.delete(story);
        session.touch();
        broadcastStories(session);
    }

    // ---------- internals ----------

    private Story newStory(Session session, String title, String description, String referenceUrl) {
        Story story = new Story();
        story.setSession(session);
        story.setTitle(title.trim());
        story.setDescription(description == null || description.isBlank() ? null : description);
        story.setReferenceUrl(referenceUrl == null || referenceUrl.isBlank() ? null : referenceUrl.trim());
        story.setOrderIndex(stories.countBySessionId(session.getId()));
        return story;
    }

    private void reorder(Session session, Story story, int newIndex) {
        List<Story> ordered = new ArrayList<>(stories.findBySessionIdOrderByOrderIndexAsc(session.getId()));
        ordered.removeIf(s -> s.getId().equals(story.getId()));
        int target = Math.max(0, Math.min(newIndex, ordered.size()));
        ordered.add(target, story);
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setOrderIndex(i);
        }
    }

    private Story requireStory(Session session, UUID storyId) {
        return stories.findByIdAndSessionId(storyId, session.getId())
                .orElseThrow(() -> ApiException.notFound("Story not found"));
    }

    private void broadcastStories(Session session) {
        List<StoryDto> all = stories.findBySessionIdOrderByOrderIndexAsc(session.getId()).stream()
                .map(StoryDto::of)
                .toList();
        broadcaster.toSession(session.getCode(), WsEvent.STORIES_CHANGED, all);
    }
}
