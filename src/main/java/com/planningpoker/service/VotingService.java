package com.planningpoker.service;

import com.planningpoker.domain.*;
import com.planningpoker.dto.Dtos.*;
import com.planningpoker.repo.*;
import com.planningpoker.security.AuthPrincipal;
import com.planningpoker.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Round orchestration: story selection, voting, reveal, reset, estimates.
 *
 * All vote/reveal mutations for a session are serialized on a per-session
 * lock so the auto-reveal condition (FR-19) is evaluated race-free when the
 * last voters submit simultaneously (TST-5). Transactions are managed
 * explicitly so the commit happens while the lock is still held.
 */
@Service
public class VotingService {

    private final Map<UUID, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

    private final SessionService sessionService;
    private final StoryRepository stories;
    private final RoundRepository rounds;
    private final VoteRepository votes;
    private final ParticipantRepository participants;
    private final StatisticsService statisticsService;
    private final Broadcaster broadcaster;
    private final TransactionTemplate tx;

    public VotingService(SessionService sessionService, StoryRepository stories, RoundRepository rounds,
                         VoteRepository votes, ParticipantRepository participants,
                         StatisticsService statisticsService, Broadcaster broadcaster,
                         TransactionTemplate tx) {
        this.sessionService = sessionService;
        this.stories = stories;
        this.rounds = rounds;
        this.votes = votes;
        this.participants = participants;
        this.statisticsService = statisticsService;
        this.broadcaster = broadcaster;
        this.tx = tx;
    }

    // ---------- story selection (FR-14) ----------

    public void selectStory(String code, UUID storyId, AuthPrincipal principal) {
        withSessionLock(code, session -> {
            sessionService.requireFacilitator(session, principal);
            Story story = stories.findByIdAndSessionId(storyId, session.getId())
                    .orElseThrow(() -> ApiException.notFound("Story not found"));

            // only one story in VOTING at a time
            stories.findFirstBySessionIdAndStatus(session.getId(), StoryStatus.VOTING).ifPresent(current -> {
                if (!current.getId().equals(story.getId())) {
                    rounds.findFirstByStoryIdAndState(current.getId(), RoundState.OPEN)
                            .ifPresent(r -> r.setState(RoundState.RESET));
                    current.setStatus(StoryStatus.PENDING);
                }
            });

            if (story.getStatus() != StoryStatus.VOTING) {
                story.setStatus(StoryStatus.VOTING);
            }
            if (rounds.findFirstByStoryIdAndState(story.getId(), RoundState.OPEN).isEmpty()) {
                Round round = new Round();
                round.setStory(story);
                rounds.save(round);
            }
            session.touch();
            broadcaster.toSession(code, WsEvent.STORY_SELECTED, StoryDto.of(story));
            return null;
        });
    }

    // ---------- voting (FR-15..FR-18) ----------

    public void vote(String code, AuthPrincipal principal, String cardValue) {
        withSessionLock(code, session -> {
            Participant participant = sessionService.requireMember(session, principal);
            if (participant.getRole() != Role.VOTER) {
                throw ApiException.forbidden("Only voters may submit votes");
            }
            Round round = requireOpenRound(session);
            if (cardValue == null || !session.getDeck().getValues().contains(cardValue)) {
                throw ApiException.badRequest("INVALID_CARD", "Card value is not part of the session deck");
            }

            Vote existing = votes.findByRoundIdAndParticipantId(round.getId(), participant.getId()).orElse(null);
            if (existing != null) {
                if (!session.isAllowVoteChange()) {
                    throw ApiException.conflict("VOTE_LOCKED", "Vote changes are disabled for this session");
                }
                existing.setCardValue(cardValue);
                existing.setSubmittedAt(Instant.now());
            } else {
                Vote vote = new Vote();
                vote.setRound(round);
                vote.setParticipant(participant);
                vote.setCardValue(cardValue);
                votes.save(vote);
            }
            session.touch();

            // FR-16/FR-17: broadcast who voted, never the value, before reveal.
            broadcaster.toSession(code, WsEvent.VOTE_STATUS,
                    Map.of("participantId", participant.getId(), "hasVoted", true));

            if (session.isAutoReveal() && allEligibleVotersVoted(session, round)) {
                doReveal(session, round);
            }
            return null;
        });
    }

    // ---------- reveal / reset / estimate (FR-19..FR-23) ----------

    public void reveal(String code, AuthPrincipal principal) {
        withSessionLock(code, session -> {
            sessionService.requireFacilitator(session, principal);
            Round round = requireOpenRound(session);
            // NFR-8: partial reveals are allowed; no minimum vote count enforced.
            doReveal(session, round);
            return null;
        });
    }

    public void reset(String code, AuthPrincipal principal) {
        withSessionLock(code, session -> {
            sessionService.requireFacilitator(session, principal);
            Story story = stories.findFirstBySessionIdAndStatus(session.getId(), StoryStatus.VOTING)
                    .orElseThrow(() -> ApiException.conflict("NO_ACTIVE_STORY", "No story is being voted on"));
            rounds.findFirstByStoryIdAndState(story.getId(), RoundState.OPEN)
                    .ifPresent(r -> r.setState(RoundState.RESET));
            rounds.findFirstByStoryIdAndState(story.getId(), RoundState.REVEALED)
                    .ifPresent(r -> r.setState(RoundState.RESET));
            Round fresh = new Round();
            fresh.setStory(story);
            rounds.save(fresh);
            session.touch();
            broadcaster.toSession(code, WsEvent.ROUND_RESET, StoryDto.of(story));
            return null;
        });
    }

    public void estimate(String code, UUID storyId, String value, AuthPrincipal principal) {
        withSessionLock(code, session -> {
            sessionService.requireFacilitator(session, principal);
            Story story = stories.findByIdAndSessionId(storyId, session.getId())
                    .orElseThrow(() -> ApiException.notFound("Story not found"));
            // FR-23: the recorded estimate may be any agreed value.
            story.setFinalEstimate(value.trim());
            story.setStatus(StoryStatus.ESTIMATED);
            rounds.findFirstByStoryIdAndState(story.getId(), RoundState.OPEN)
                    .ifPresent(r -> {
                        r.setState(RoundState.REVEALED);
                        r.setRevealedAt(Instant.now());
                    });
            session.touch();
            broadcaster.toSession(code, WsEvent.STORY_ESTIMATED, StoryDto.of(story));
            return null;
        });
    }

    // ---------- internals ----------

    private void doReveal(Session session, Round round) {
        round.setState(RoundState.REVEALED);
        round.setRevealedAt(Instant.now());
        List<Vote> roundVotes = votes.findByRoundId(round.getId());
        List<VoteDto> voteDtos = roundVotes.stream()
                .map(v -> new VoteDto(v.getParticipant().getId(), v.getParticipant().getDisplayName(),
                        v.getCardValue(), v.getSubmittedAt()))
                .toList();
        StatsDto stats = statisticsService.compute(roundVotes.stream().map(Vote::getCardValue).toList());
        // FR-20/FR-21: one simultaneous broadcast carrying all values + statistics.
        broadcaster.toSession(session.getCode(), WsEvent.ROUND_REVEALED,
                new RoundDto(round.getId(), round.getState(), round.getRevealedAt(), voteDtos, stats));
    }

    private boolean allEligibleVotersVoted(Session session, Round round) {
        List<Participant> active = participants.findBySessionIdAndActiveTrueOrderByJoinedAtAsc(session.getId());
        List<UUID> eligible = active.stream()
                // NFR-8: disconnected voters don't block auto-reveal
                .filter(p -> p.getRole() == Role.VOTER && p.getConnectionStatus() == ConnectionStatus.CONNECTED)
                .map(Participant::getId)
                .toList();
        if (eligible.isEmpty()) {
            return false;
        }
        var votedIds = votes.findByRoundId(round.getId()).stream()
                .map(v -> v.getParticipant().getId())
                .collect(java.util.stream.Collectors.toSet());
        return votedIds.containsAll(eligible);
    }

    private Round requireOpenRound(Session session) {
        Story story = stories.findFirstBySessionIdAndStatus(session.getId(), StoryStatus.VOTING)
                .orElseThrow(() -> ApiException.conflict("NO_ACTIVE_STORY", "No story is being voted on"));
        return rounds.findFirstByStoryIdAndState(story.getId(), RoundState.OPEN)
                .orElseThrow(() -> ApiException.conflict("NO_OPEN_ROUND", "Round is not open (already revealed?)"));
    }

    /**
     * Serializes all round mutations per session and runs them inside a
     * transaction that commits before the lock is released.
     */
    private <T> T withSessionLock(String code, java.util.function.Function<Session, T> work) {
        // resolve the session id outside the lock map keyed by id
        Session probe = sessionService.requireActiveSession(code);
        ReentrantLock lock = sessionLocks.computeIfAbsent(probe.getId(), id -> new ReentrantLock());
        lock.lock();
        try {
            return tx.execute(status -> {
                Session session = sessionService.requireActiveSession(code);
                return work.apply(session);
            });
        } finally {
            lock.unlock();
        }
    }
}
