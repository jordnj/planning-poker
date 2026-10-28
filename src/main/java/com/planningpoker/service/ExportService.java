package com.planningpoker.service;

import com.planningpoker.domain.*;
import com.planningpoker.dto.Dtos.*;
import com.planningpoker.repo.RoundRepository;
import com.planningpoker.repo.StoryRepository;
import com.planningpoker.repo.VoteRepository;
import com.planningpoker.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles session results for export (FR-27, FR-28).
 *
 * Only revealed rounds are included; hidden open-round votes never leave the
 * server (SEC-4). Story titles, descriptions, reference URLs and participant
 * names pass through as the client-side ciphertext they were stored as — the
 * browser decrypts them and writes the CSV/JSON file, because this service has
 * no key for those fields.
 */
@Service
public class ExportService {

    private final SessionService sessionService;
    private final StoryRepository stories;
    private final RoundRepository rounds;
    private final VoteRepository votes;
    private final StatisticsService statisticsService;

    public ExportService(SessionService sessionService, StoryRepository stories,
                         RoundRepository rounds, VoteRepository votes,
                         StatisticsService statisticsService) {
        this.sessionService = sessionService;
        this.stories = stories;
        this.rounds = rounds;
        this.votes = votes;
        this.statisticsService = statisticsService;
    }

    @Transactional(readOnly = true)
    public ExportBundle export(String code, AuthPrincipal principal) {
        Session session = sessionService.requireSession(code);
        sessionService.requireFacilitator(session, principal);

        List<ExportStory> storyList = new ArrayList<>();
        for (Story story : stories.findBySessionIdOrderByOrderIndexAsc(session.getId())) {
            List<ExportRound> roundList = new ArrayList<>();
            for (Round round : rounds.findByStoryIdOrderByCreatedAtAsc(story.getId())) {
                if (round.getState() != RoundState.REVEALED) {
                    continue;
                }
                List<Vote> roundVotes = votes.findByRoundId(round.getId());
                List<ExportVote> voteList = roundVotes.stream()
                        .map(v -> new ExportVote(
                                v.getParticipant().getDisplayName(),
                                v.getCardValue(),
                                v.getSubmittedAt()))
                        .toList();
                StatsDto stats = statisticsService.compute(
                        roundVotes.stream().map(Vote::getCardValue).toList());
                roundList.add(new ExportRound(round.getRevealedAt(), voteList, stats));
            }
            storyList.add(new ExportStory(
                    story.getTitle(),
                    story.getDescription(),
                    story.getReferenceUrl(),
                    story.getStatus(),
                    story.getFinalEstimate(),
                    roundList));
        }

        return new ExportBundle(
                session.getCode(),
                session.getName(),
                session.getStatus(),
                session.getCreatedAt(),
                session.getDeck().getName(),
                storyList);
    }
}
