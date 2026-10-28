package com.planningpoker.service;

import com.planningpoker.config.AppProperties;
import com.planningpoker.domain.*;
import com.planningpoker.dto.Dtos.*;
import com.planningpoker.repo.*;
import com.planningpoker.security.AuthPrincipal;
import com.planningpoker.security.JwtService;
import com.planningpoker.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class SessionService {

    /** Unambiguous alphabet (no 0/O, 1/I/L) for readable, high-entropy codes (FR-2, NFR-10). */
    private static final char[] CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
    private static final int CODE_LENGTH = 8;

    private final SecureRandom random = new SecureRandom();

    private final SessionRepository sessions;
    private final ParticipantRepository participants;
    private final StoryRepository stories;
    private final RoundRepository rounds;
    private final VoteRepository votes;
    private final DeckRepository decks;
    private final JwtService jwtService;
    private final StatisticsService statisticsService;
    private final Broadcaster broadcaster;
    private final AppProperties props;

    public SessionService(SessionRepository sessions, ParticipantRepository participants,
                          StoryRepository stories, RoundRepository rounds, VoteRepository votes,
                          DeckRepository decks, JwtService jwtService,
                          StatisticsService statisticsService, Broadcaster broadcaster,
                          AppProperties props) {
        this.sessions = sessions;
        this.participants = participants;
        this.stories = stories;
        this.rounds = rounds;
        this.votes = votes;
        this.decks = decks;
        this.jwtService = jwtService;
        this.statisticsService = statisticsService;
        this.broadcaster = broadcaster;
        this.props = props;
    }

    // ---------- lifecycle ----------

    @Transactional
    public CreateSessionResponse create(CreateSessionRequest req, AuthPrincipal maybeUser, String requestOrigin) {
        Deck deck = resolveDeck(req, maybeUser);

        Session session = new Session();
        session.setCode(generateCode());
        session.setName(req.name().trim());
        session.setDeck(deck);
        session.setAutoReveal(Boolean.TRUE.equals(req.autoReveal()));
        session.setAllowObservers(req.allowObservers() == null || req.allowObservers());
        session.setAllowVoteChange(req.allowVoteChange() == null || req.allowVoteChange());
        session = sessions.save(session);

        Participant facilitator = new Participant();
        facilitator.setSession(session);
        facilitator.setDisplayName(req.facilitatorName().trim());
        facilitator.setRole(Role.FACILITATOR);
        if (maybeUser != null && maybeUser.isUser()) {
            facilitator.setUserId(maybeUser.id());
        }
        facilitator = participants.save(facilitator);
        session.setFacilitatorParticipantId(facilitator.getId());

        String token = jwtService.issueParticipantToken(facilitator, session.getCode());
        String base = props.baseUrl() != null && !props.baseUrl().isBlank() ? props.baseUrl() : requestOrigin;
        String joinUrl = (base == null ? "" : base) + "/?join=" + session.getCode();

        SessionSnapshot snapshot = snapshot(session);
        return new CreateSessionResponse(snapshot, toDto(facilitator, false), token, joinUrl);
    }

    @Transactional
    public JoinResponse join(String code, JoinRequest req, AuthPrincipal maybeUser) {
        Session session = requireActiveSession(code);

        Role role = Role.VOTER;
        if (req.role() != null && !req.role().isBlank()) {
            try {
                role = Role.valueOf(req.role().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("INVALID_ROLE", "Role must be VOTER or OBSERVER");
            }
        }
        if (role == Role.FACILITATOR) {
            throw ApiException.badRequest("INVALID_ROLE", "Cannot join as facilitator");
        }
        if (role == Role.OBSERVER && !session.isAllowObservers()) {
            throw ApiException.forbidden("Observers are not allowed in this session");
        }

        Participant participant = new Participant();
        participant.setSession(session);
        participant.setDisplayName(req.displayName().trim());
        participant.setRole(role);
        if (maybeUser != null && maybeUser.isUser()) {
            participant.setUserId(maybeUser.id());
        }
        participant = participants.save(participant);
        session.touch();

        String token = jwtService.issueParticipantToken(participant, session.getCode());
        SessionSnapshot snapshot = snapshot(session);
        broadcaster.toSession(code, WsEvent.PARTICIPANT_JOINED, toDto(participant, false));
        return new JoinResponse(snapshot, toDto(participant, hasVoted(session, participant)), token);
    }

    @Transactional
    public void close(String code, AuthPrincipal principal) {
        Session session = requireActiveSession(code);
        requireFacilitator(session, principal);
        session.setStatus(SessionStatus.CLOSED);
        broadcaster.toSession(code, WsEvent.SESSION_CLOSED, null);
    }

    @Transactional
    public void leaveOrKick(String code, UUID targetParticipantId, AuthPrincipal principal) {
        Session session = requireSession(code);
        Participant actor = requireMember(session, principal);
        Participant target = participants.findByIdAndSessionId(targetParticipantId, session.getId())
                .orElseThrow(() -> ApiException.notFound("Participant not found"));

        boolean self = actor.getId().equals(target.getId());
        if (!self && actor.getRole() != Role.FACILITATOR) {
            throw ApiException.forbidden("Only the facilitator may remove other participants");
        }
        target.setActive(false);
        target.setConnectionStatus(ConnectionStatus.DISCONNECTED);
        session.touch();

        // If the facilitator leaves, promote the oldest active participant (FR-5).
        if (target.getId().equals(session.getFacilitatorParticipantId())) {
            reassignFacilitator(session);
        }
        broadcaster.toSession(code, WsEvent.PARTICIPANT_LEFT, toDto(target, false));
    }

    @Transactional
    public void changeRole(String code, UUID targetParticipantId, String newRole, AuthPrincipal principal) {
        Session session = requireActiveSession(code);
        requireFacilitator(session, principal);
        Participant target = participants.findByIdAndSessionId(targetParticipantId, session.getId())
                .filter(Participant::isActive)
                .orElseThrow(() -> ApiException.notFound("Participant not found"));

        Role role;
        try {
            role = Role.valueOf(newRole.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_ROLE", "Unknown role: " + newRole);
        }

        if (role == Role.FACILITATOR) {
            // transfer facilitation (FR-5): demote current facilitator to voter
            Participant current = participants.findById(session.getFacilitatorParticipantId()).orElse(null);
            if (current != null && !current.getId().equals(target.getId())) {
                current.setRole(Role.VOTER);
                broadcaster.toSession(code, WsEvent.PARTICIPANT_UPDATED, toDto(current, hasVoted(session, current)));
            }
            session.setFacilitatorParticipantId(target.getId());
            broadcaster.toSession(code, WsEvent.FACILITATOR_CHANGED, target.getId());
        }
        target.setRole(role);
        session.touch();
        broadcaster.toSession(code, WsEvent.PARTICIPANT_UPDATED, toDto(target, hasVoted(session, target)));
    }

    @Transactional
    public void connectionChanged(UUID participantId, ConnectionStatus status) {
        participants.findById(participantId).ifPresent(p -> {
            if (!p.isActive()) {
                return;
            }
            p.setConnectionStatus(status);
            String code = p.getSession().getCode();
            p.getSession().touch();
            broadcaster.toSession(code, WsEvent.CONNECTION_CHANGED, toDto(p, hasVoted(p.getSession(), p)));
        });
    }

    // ---------- queries ----------

    @Transactional(readOnly = true)
    public SessionSnapshot getSnapshot(String code, AuthPrincipal principal) {
        Session session = requireSession(code);
        requireMember(session, principal);
        return snapshot(session);
    }

    /** Snapshot without an authorization check; callers must have verified membership already. */
    @Transactional(readOnly = true)
    public SessionSnapshot snapshotByCode(String code) {
        return snapshot(requireSession(code));
    }

    public SessionSnapshot snapshot(Session session) {
        Story activeStory = stories.findFirstBySessionIdAndStatus(session.getId(), StoryStatus.VOTING).orElse(null);
        Round activeRound = activeStory == null ? null
                : rounds.findFirstByStoryIdAndState(activeStory.getId(), RoundState.OPEN)
                .or(() -> rounds.findFirstByStoryIdAndState(activeStory.getId(), RoundState.REVEALED))
                .orElse(null);

        Set<UUID> votedIds = activeRound == null ? Set.of()
                : votes.findByRoundId(activeRound.getId()).stream()
                .map(v -> v.getParticipant().getId())
                .collect(java.util.stream.Collectors.toSet());

        List<ParticipantDto> participantDtos = participants
                .findBySessionIdAndActiveTrueOrderByJoinedAtAsc(session.getId()).stream()
                .map(p -> toDto(p, votedIds.contains(p.getId())))
                .toList();

        List<StoryDto> storyDtos = stories.findBySessionIdOrderByOrderIndexAsc(session.getId()).stream()
                .map(StoryDto::of)
                .toList();

        RoundDto roundDto = null;
        if (activeRound != null) {
            // SEC-4 / RT-4: vote values are only ever serialized after reveal.
            List<VoteDto> voteDtos = null;
            StatsDto stats = null;
            if (activeRound.getState() == RoundState.REVEALED) {
                List<Vote> roundVotes = votes.findByRoundId(activeRound.getId());
                voteDtos = roundVotes.stream()
                        .map(v -> new VoteDto(v.getParticipant().getId(), v.getParticipant().getDisplayName(),
                                v.getCardValue(), v.getSubmittedAt()))
                        .toList();
                stats = statisticsService.compute(roundVotes.stream().map(Vote::getCardValue).toList());
            }
            roundDto = new RoundDto(activeRound.getId(), activeRound.getState(), activeRound.getRevealedAt(),
                    voteDtos, stats);
        }

        return new SessionSnapshot(
                session.getCode(),
                session.getName(),
                session.getStatus(),
                new SessionSettingsDto(session.isAutoReveal(), session.isAllowObservers(), session.isAllowVoteChange()),
                session.getFacilitatorParticipantId(),
                DeckDto.of(session.getDeck()),
                participantDtos,
                storyDtos,
                activeStory == null ? null : StoryDto.of(activeStory),
                roundDto
        );
    }

    // ---------- shared authorization helpers (SEC-3) ----------

    public Session requireSession(String code) {
        // the message deliberately omits the code so it cannot leak into logs
        return sessions.findByCode(code)
                .orElseThrow(() -> ApiException.notFound("Session not found"));
    }

    public Session requireActiveSession(String code) {
        Session session = requireSession(code);
        if (session.getStatus() != SessionStatus.ACTIVE) {
            throw ApiException.conflict("SESSION_CLOSED", "Session is closed");
        }
        return session;
    }

    /** Verifies the caller holds a participant token for this session and is still a member. */
    public Participant requireMember(Session session, AuthPrincipal principal) {
        if (principal == null || !principal.isParticipant()
                || !session.getCode().equals(principal.sessionCode())) {
            throw ApiException.forbidden("Not a member of this session");
        }
        return participants.findByIdAndSessionId(principal.id(), session.getId())
                .filter(Participant::isActive)
                .orElseThrow(() -> ApiException.forbidden("Not a member of this session"));
    }

    public Participant requireFacilitator(Session session, AuthPrincipal principal) {
        Participant participant = requireMember(session, principal);
        if (participant.getRole() != Role.FACILITATOR) {
            throw ApiException.forbidden("Only the facilitator may perform this action");
        }
        return participant;
    }

    // ---------- internals ----------

    private void reassignFacilitator(Session session) {
        List<Participant> active = participants.findBySessionIdAndActiveTrueOrderByJoinedAtAsc(session.getId());
        Participant next = active.stream().filter(p -> p.getRole() != Role.OBSERVER).findFirst()
                .orElse(active.isEmpty() ? null : active.get(0));
        if (next != null) {
            next.setRole(Role.FACILITATOR);
            session.setFacilitatorParticipantId(next.getId());
            broadcaster.toSession(session.getCode(), WsEvent.FACILITATOR_CHANGED, next.getId());
            broadcaster.toSession(session.getCode(), WsEvent.PARTICIPANT_UPDATED, toDto(next, hasVoted(session, next)));
        } else {
            session.setFacilitatorParticipantId(null);
        }
    }

    private Deck resolveDeck(CreateSessionRequest req, AuthPrincipal maybeUser) {
        if (req.customDeckValues() != null && !req.customDeckValues().isEmpty()) {
            Deck deck = new Deck();
            deck.setName(req.customDeckName() == null || req.customDeckName().isBlank()
                    ? "Custom" : req.customDeckName().trim());
            deck.setValues(req.customDeckValues().stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
            deck.setBuiltIn(false);
            if (maybeUser != null && maybeUser.isUser()) {
                deck.setOwnerUserId(maybeUser.id());
            }
            if (deck.getValues().isEmpty()) {
                throw ApiException.badRequest("INVALID_DECK", "Custom deck needs at least one value");
            }
            return decks.save(deck);
        }
        if (req.deckId() == null) {
            throw ApiException.badRequest("INVALID_DECK", "deckId or customDeckValues is required");
        }
        return decks.findById(req.deckId())
                .orElseThrow(() -> ApiException.badRequest("INVALID_DECK", "Unknown deck"));
    }

    private String generateCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
            }
            String code = sb.toString();
            if (!sessions.existsByCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Could not generate a unique session code");
    }

    private boolean hasVoted(Session session, Participant participant) {
        return stories.findFirstBySessionIdAndStatus(session.getId(), StoryStatus.VOTING)
                .flatMap(story -> rounds.findFirstByStoryIdAndState(story.getId(), RoundState.OPEN))
                .flatMap(round -> votes.findByRoundIdAndParticipantId(round.getId(), participant.getId()))
                .isPresent();
    }

    public ParticipantDto toDto(Participant p, boolean hasVoted) {
        return new ParticipantDto(p.getId(), p.getDisplayName(), p.getRole(),
                p.getConnectionStatus(), hasVoted, p.getJoinedAt());
    }
}
