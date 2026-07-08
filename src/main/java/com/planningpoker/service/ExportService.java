package com.planningpoker.service;

import com.planningpoker.domain.*;
import com.planningpoker.dto.Dtos.StatsDto;
import com.planningpoker.repo.RoundRepository;
import com.planningpoker.repo.StoryRepository;
import com.planningpoker.repo.VoteRepository;
import com.planningpoker.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Session results export (FR-27, FR-28). Only revealed rounds' votes are
 * exported; hidden (open-round) votes never leave the server (SEC-4).
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
    public Map<String, Object> exportJson(String code, AuthPrincipal principal) {
        Session session = sessionService.requireSession(code);
        sessionService.requireFacilitator(session, principal);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("sessionCode", session.getCode());
        root.put("sessionName", session.getName());
        root.put("status", session.getStatus().name());
        root.put("createdAt", session.getCreatedAt());
        root.put("deck", session.getDeck().getName());

        List<Map<String, Object>> storyList = new ArrayList<>();
        for (Story story : stories.findBySessionIdOrderByOrderIndexAsc(session.getId())) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("title", story.getTitle());
            s.put("status", story.getStatus().name());
            s.put("finalEstimate", story.getFinalEstimate());
            s.put("referenceUrl", story.getReferenceUrl());

            List<Map<String, Object>> roundList = new ArrayList<>();
            for (Round round : rounds.findByStoryIdOrderByCreatedAtAsc(story.getId())) {
                if (round.getState() != RoundState.REVEALED) {
                    continue;
                }
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("revealedAt", round.getRevealedAt());
                List<Vote> roundVotes = votes.findByRoundId(round.getId());
                List<Map<String, Object>> voteList = new ArrayList<>();
                for (Vote vote : roundVotes) {
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("participant", vote.getParticipant().getDisplayName());
                    v.put("cardValue", vote.getCardValue());
                    v.put("submittedAt", vote.getSubmittedAt());
                    voteList.add(v);
                }
                r.put("votes", voteList);
                StatsDto stats = statisticsService.compute(roundVotes.stream().map(Vote::getCardValue).toList());
                r.put("statistics", stats);
                roundList.add(r);
            }
            s.put("rounds", roundList);
            storyList.add(s);
        }
        root.put("stories", storyList);
        return root;
    }

    @Transactional(readOnly = true)
    public String exportCsv(String code, AuthPrincipal principal) {
        Session session = sessionService.requireSession(code);
        sessionService.requireFacilitator(session, principal);

        StringBuilder sb = new StringBuilder();
        sb.append("story_title,story_status,final_estimate,revealed_at,participant,card_value,submitted_at\n");
        for (Story story : stories.findBySessionIdOrderByOrderIndexAsc(session.getId())) {
            boolean anyRow = false;
            for (Round round : rounds.findByStoryIdOrderByCreatedAtAsc(story.getId())) {
                if (round.getState() != RoundState.REVEALED) {
                    continue;
                }
                for (Vote vote : votes.findByRoundId(round.getId())) {
                    appendRow(sb, story, round, vote);
                    anyRow = true;
                }
            }
            if (!anyRow) {
                appendRow(sb, story, null, null);
            }
        }
        return sb.toString();
    }

    private void appendRow(StringBuilder sb, Story story, Round round, Vote vote) {
        sb.append(csv(story.getTitle())).append(',')
                .append(story.getStatus().name()).append(',')
                .append(csv(story.getFinalEstimate())).append(',')
                .append(round == null || round.getRevealedAt() == null ? "" : round.getRevealedAt()).append(',')
                .append(vote == null ? "" : csv(vote.getParticipant().getDisplayName())).append(',')
                .append(vote == null ? "" : csv(vote.getCardValue())).append(',')
                .append(vote == null ? "" : vote.getSubmittedAt())
                .append('\n');
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
