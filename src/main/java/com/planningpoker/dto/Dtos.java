package com.planningpoker.dto;

import com.planningpoker.domain.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request/response records for the REST API and STOMP payloads. */
public final class Dtos {

    private Dtos() {
    }

    // ---------- requests ----------

    public record CreateSessionRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 50) String facilitatorName,
            UUID deckId,
            List<@NotBlank @Size(max = 50) String> customDeckValues,
            @Size(max = 100) String customDeckName,
            Boolean autoReveal,
            Boolean allowObservers,
            Boolean allowVoteChange
    ) {
    }

    public record JoinRequest(
            @NotBlank @Size(max = 50) String displayName,
            String role // VOTER (default) or OBSERVER
    ) {
    }

    public record StoryRequest(
            @NotBlank @Size(max = 255) String title,
            @Size(max = 10000) String description,
            @Size(max = 500) String referenceUrl
    ) {
    }

    public record StoryUpdateRequest(
            @Size(max = 255) String title,
            @Size(max = 10000) String description,
            @Size(max = 500) String referenceUrl,
            Integer orderIndex
    ) {
    }

    public record ImportStoriesRequest(@NotBlank @Size(max = 100000) String text) {
    }

    public record EstimateRequest(@NotBlank @Size(max = 50) String value) {
    }

    public record RoleChangeRequest(@NotBlank String role) {
    }

    public record CreateDeckRequest(
            @NotBlank @Size(max = 100) String name,
            @jakarta.validation.constraints.NotEmpty List<@NotBlank @Size(max = 50) String> values
    ) {
    }

    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 50) String username,
            @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 100) String password
    ) {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    // ---------- STOMP payloads (client -> server) ----------

    public record VoteMessage(String cardValue) {
    }

    public record SelectStoryMessage(UUID storyId) {
    }

    // ---------- responses ----------

    public record AuthResponse(String token, UUID userId, String username) {
    }

    public record DeckDto(UUID id, String name, List<String> values, boolean builtIn) {
        public static DeckDto of(Deck d) {
            return new DeckDto(d.getId(), d.getName(), d.getValues(), d.isBuiltIn());
        }
    }

    public record ParticipantDto(
            UUID id,
            String displayName,
            Role role,
            ConnectionStatus connectionStatus,
            boolean hasVoted,
            Instant joinedAt
    ) {
    }

    public record StoryDto(
            UUID id,
            String title,
            String description,
            String referenceUrl,
            StoryStatus status,
            String finalEstimate,
            int orderIndex
    ) {
        public static StoryDto of(Story s) {
            return new StoryDto(s.getId(), s.getTitle(), s.getDescription(), s.getReferenceUrl(),
                    s.getStatus(), s.getFinalEstimate(), s.getOrderIndex());
        }
    }

    public record VoteDto(UUID participantId, String displayName, String cardValue, Instant submittedAt) {
    }

    public record StatsDto(
            int totalVotes,
            int numericVotes,
            Double min,
            Double max,
            Double average,
            Double median,
            Map<String, Integer> distribution,
            Map<String, Integer> nonNumeric,
            boolean consensus
    ) {
    }

    public record RoundDto(UUID id, RoundState state, Instant revealedAt,
                           List<VoteDto> votes, StatsDto stats) {
    }

    public record SessionSettingsDto(boolean autoReveal, boolean allowObservers, boolean allowVoteChange) {
    }

    /** Full state a (re)connecting client needs to render the session (RT-3). */
    public record SessionSnapshot(
            String code,
            String name,
            SessionStatus status,
            SessionSettingsDto settings,
            UUID facilitatorParticipantId,
            DeckDto deck,
            List<ParticipantDto> participants,
            List<StoryDto> stories,
            StoryDto activeStory,
            RoundDto activeRound
    ) {
    }

    public record CreateSessionResponse(
            SessionSnapshot session,
            ParticipantDto participant,
            String token,
            String joinUrl
    ) {
    }

    public record JoinResponse(
            SessionSnapshot session,
            ParticipantDto participant,
            String token
    ) {
    }

    // ---------- broadcast events (server -> clients) ----------

    public record WsEvent(String type, Object payload) {
        public static final String PARTICIPANT_JOINED = "PARTICIPANT_JOINED";
        public static final String PARTICIPANT_LEFT = "PARTICIPANT_LEFT";
        public static final String PARTICIPANT_UPDATED = "PARTICIPANT_UPDATED";
        public static final String CONNECTION_CHANGED = "CONNECTION_CHANGED";
        public static final String VOTE_STATUS = "VOTE_STATUS";
        public static final String ROUND_REVEALED = "ROUND_REVEALED";
        public static final String ROUND_RESET = "ROUND_RESET";
        public static final String STORY_SELECTED = "STORY_SELECTED";
        public static final String STORIES_CHANGED = "STORIES_CHANGED";
        public static final String STORY_ESTIMATED = "STORY_ESTIMATED";
        public static final String SESSION_CLOSED = "SESSION_CLOSED";
        public static final String FACILITATOR_CHANGED = "FACILITATOR_CHANGED";
    }
}
