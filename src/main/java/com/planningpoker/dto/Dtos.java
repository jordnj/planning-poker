package com.planningpoker.dto;

import com.planningpoker.domain.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request/response records for the REST API and STOMP payloads.
 *
 * Fields carrying client-encrypted values (story title/description/reference
 * URL, participant display names) are bounded by CIPHERTEXT size rather than
 * by their plaintext meaning — the server cannot read them, so it can only
 * cap how much it is asked to store. AES-GCM adds 28 bytes and base64
 * inflates by 4/3, so each bound is roughly 1.4x the plaintext byte ceiling
 * with headroom. Plaintext limits are enforced in the browser by the matching
 * maxlength attributes; see docs/PRIVACY.md for what that trade buys.
 */
public final class Dtos {

    /** Bounds for client-encrypted fields, derived from plaintext ceilings. */
    private static final int ENC_NAME_MAX = 1_000;        // 50 chars
    private static final int ENC_TITLE_MAX = 4_000;       // 255 chars
    private static final int ENC_URL_MAX = 4_000;         // 500 chars
    private static final int ENC_DESCRIPTION_MAX = 60_000; // 10,000 chars

    private Dtos() {
    }

    // ---------- requests ----------

    public record CreateSessionRequest(
            // plaintext in transit, encrypted at rest by the server
            @NotBlank @Size(max = 100) String name,
            // client-encrypted
            @NotBlank @Size(max = ENC_NAME_MAX) String facilitatorName,
            UUID deckId,
            List<@NotBlank @Size(max = 50) String> customDeckValues,
            @Size(max = 100) String customDeckName,
            Boolean autoReveal,
            Boolean allowObservers,
            Boolean allowVoteChange
    ) {
    }

    public record JoinRequest(
            // client-encrypted
            @NotBlank @Size(max = ENC_NAME_MAX) String displayName,
            String role // VOTER (default) or OBSERVER
    ) {
    }

    /** Story text fields are client-encrypted and opaque to the server. */
    public record StoryRequest(
            @NotBlank @Size(max = ENC_TITLE_MAX) String title,
            @Size(max = ENC_DESCRIPTION_MAX) String description,
            @Size(max = ENC_URL_MAX) String referenceUrl
    ) {
    }

    public record StoryUpdateRequest(
            @Size(max = ENC_TITLE_MAX) String title,
            @Size(max = ENC_DESCRIPTION_MAX) String description,
            @Size(max = ENC_URL_MAX) String referenceUrl,
            Integer orderIndex
    ) {
    }

    /**
     * Paste-import: the browser splits the pasted text into lines and encrypts
     * each one, so the server receives a list of opaque titles rather than a
     * blob of readable text.
     */
    public record ImportStoriesRequest(
            @NotEmpty @Size(max = 500) List<@NotBlank @Size(max = ENC_TITLE_MAX) String> titles
    ) {
    }

    public record EstimateRequest(@NotBlank @Size(max = 50) String value) {
    }

    public record RoleChangeRequest(@NotBlank String role) {
    }

    public record CreateDeckRequest(
            @NotBlank @Size(max = 100) String name,
            @NotEmpty List<@NotBlank @Size(max = 50) String> values
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

    // ---------- export ----------

    /**
     * Raw material for an export. The server cannot render CSV or JSON with
     * readable story titles and participant names, because it holds no key for
     * them — those fields ship as ciphertext and the browser decrypts and
     * formats the file. Card values and the session name are server-encrypted
     * at rest, so they appear here in the clear.
     */
    public record ExportBundle(
            String sessionCode,
            String sessionName,
            SessionStatus status,
            Instant createdAt,
            String deckName,
            List<ExportStory> stories
    ) {
    }

    public record ExportStory(
            String title,             // ciphertext
            String description,       // ciphertext
            String referenceUrl,      // ciphertext
            StoryStatus status,
            String finalEstimate,
            List<ExportRound> rounds
    ) {
    }

    public record ExportRound(Instant revealedAt, List<ExportVote> votes, StatsDto statistics) {
    }

    public record ExportVote(String displayName, String cardValue, Instant submittedAt) {
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
