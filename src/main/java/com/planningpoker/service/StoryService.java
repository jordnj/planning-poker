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

    /**
     * FR-12: paste-import. The browser has already split the pasted text into
     * one title per line and encrypted each, so this only stores opaque values
     * — it cannot inspect, trim or truncate them by meaning.
     */
    @Transactional
    public List<StoryDto> importEncryptedTitles(String code, List<String> titles, AuthPrincipal principal) {
        Session session = sessionService.requireActiveSession(code);
        sessionService.requireFacilitator(session, principal);
        List<StoryDto> created = new ArrayList<>();
        for (String title : titles) {
            if (title == null || title.isBlank()) {
                continue;
            }
            Story story = newStory(session, title, null, null);
            stories.save(story);
            created.add(StoryDto.of(story));
        }
        if (created.isEmpty()) {
            throw ApiException.badRequest("EMPTY_IMPORT", "No story titles were supplied");
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
            story.setTitle(req.title());
        }
        if (req.description() != null) {
            story.setDescription(blankToNull(req.description()));
        }
        if (req.referenceUrl() != null) {
            story.setReferenceUrl(blankToNull(req.referenceUrl()));
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
        // close the gap the deletion leaves, otherwise the next added story
        // reuses an index that is still in use and the queue order goes
        // ambiguous, breaking the reorder controls
        compactOrderIndices(session, story.getId());
        session.touch();
        broadcastStories(session);
    }

    // ---------- internals ----------

    // ciphertext is stored verbatim; only emptiness is meaningful to the server
    private Story newStory(Session session, String title, String description, String referenceUrl) {
        Story story = new Story();
        story.setSession(session);
        story.setTitle(title);
        story.setDescription(blankToNull(description));
        story.setReferenceUrl(blankToNull(referenceUrl));
        story.setOrderIndex(nextOrderIndex(session));
        return story;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** One past the highest index in use, so gaps never cause a collision. */
    private int nextOrderIndex(Session session) {
        return stories.findBySessionIdOrderByOrderIndexAsc(session.getId()).stream()
                .mapToInt(Story::getOrderIndex)
                .max()
                .orElse(-1) + 1;
    }

    private void compactOrderIndices(Session session, UUID removedStoryId) {
        List<Story> remaining = stories.findBySessionIdOrderByOrderIndexAsc(session.getId()).stream()
                .filter(s -> !s.getId().equals(removedStoryId))
                .toList();
        for (int i = 0; i < remaining.size(); i++) {
            remaining.get(i).setOrderIndex(i);
        }
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
