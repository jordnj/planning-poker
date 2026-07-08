package com.planningpoker.service;

import com.planningpoker.domain.Role;
import com.planningpoker.domain.RoundState;
import com.planningpoker.domain.SessionStatus;
import com.planningpoker.domain.StoryStatus;
import com.planningpoker.dto.Dtos.*;
import com.planningpoker.security.AuthPrincipal;
import com.planningpoker.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end service-level flow: create → join → vote (hidden) → reveal →
 * reset → estimate → export, plus authorization rules (TST-1, TST-4).
 */
@SpringBootTest
class SessionFlowIntegrationTest {

    private static final UUID FIBONACCI = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    SessionService sessionService;
    @Autowired
    StoryService storyService;
    @Autowired
    VotingService votingService;
    @Autowired
    ExportService exportService;

    private AuthPrincipal principal(UUID participantId, String code) {
        return new AuthPrincipal(AuthPrincipal.TokenType.PARTICIPANT, participantId, code, "test");
    }

    private CreateSessionResponse createSession(boolean autoReveal, boolean allowVoteChange) {
        return sessionService.create(new CreateSessionRequest(
                        "Sprint 1", "Fay", FIBONACCI, null, null, autoReveal, true, allowVoteChange),
                null, "http://localhost");
    }

    @Test
    void fullEstimationFlow() {
        CreateSessionResponse created = createSession(false, true);
        String code = created.session().code();
        assertThat(code).hasSize(8);
        assertThat(created.joinUrl()).contains(code);
        assertThat(created.participant().role()).isEqualTo(Role.FACILITATOR);

        AuthPrincipal facilitator = principal(created.participant().id(), code);
        JoinResponse v1 = sessionService.join(code, new JoinRequest("Ana", "VOTER"), null);
        JoinResponse v2 = sessionService.join(code, new JoinRequest("Ben", "VOTER"), null);
        JoinResponse obs = sessionService.join(code, new JoinRequest("Olly", "OBSERVER"), null);
        AuthPrincipal ana = principal(v1.participant().id(), code);
        AuthPrincipal ben = principal(v2.participant().id(), code);
        AuthPrincipal olly = principal(obs.participant().id(), code);

        // facilitator manages stories
        StoryDto story = storyService.add(code, new StoryRequest("Login page", "desc", null), facilitator);
        assertThatThrownBy(() -> storyService.add(code, new StoryRequest("Nope", null, null), ana))
                .isInstanceOf(ApiException.class).hasMessageContaining("facilitator");

        votingService.selectStory(code, story.id(), facilitator);
        SessionSnapshot snapshot = sessionService.getSnapshot(code, facilitator);
        assertThat(snapshot.activeStory().id()).isEqualTo(story.id());
        assertThat(snapshot.activeRound().state()).isEqualTo(RoundState.OPEN);

        // voting: observers and facilitators cannot vote (SEC-3)
        assertThatThrownBy(() -> votingService.vote(code, olly, "5"))
                .isInstanceOf(ApiException.class).hasMessageContaining("voters");
        assertThatThrownBy(() -> votingService.vote(code, facilitator, "5"))
                .isInstanceOf(ApiException.class).hasMessageContaining("voters");
        // card must belong to the deck
        assertThatThrownBy(() -> votingService.vote(code, ana, "7"))
                .isInstanceOf(ApiException.class).hasMessageContaining("deck");

        votingService.vote(code, ana, "5");
        votingService.vote(code, ben, "8");
        // vote change allowed pre-reveal (FR-18)
        votingService.vote(code, ana, "8");

        // pre-reveal: flags visible, values hidden (SEC-4 / TST-4)
        snapshot = sessionService.getSnapshot(code, ana);
        assertThat(snapshot.activeRound().votes()).isNull();
        assertThat(snapshot.activeRound().stats()).isNull();
        assertThat(snapshot.participants().stream()
                .filter(ParticipantDto::hasVoted).count()).isEqualTo(2);

        // only the facilitator reveals (SEC-3)
        assertThatThrownBy(() -> votingService.reveal(code, ana))
                .isInstanceOf(ApiException.class).hasMessageContaining("facilitator");
        votingService.reveal(code, facilitator);

        snapshot = sessionService.getSnapshot(code, ana);
        assertThat(snapshot.activeRound().state()).isEqualTo(RoundState.REVEALED);
        assertThat(snapshot.activeRound().votes()).hasSize(2)
                .allSatisfy(v -> assertThat(v.cardValue()).isEqualTo("8"));
        assertThat(snapshot.activeRound().stats().consensus()).isTrue();
        assertThat(snapshot.activeRound().stats().average()).isEqualTo(8.0);

        // votes cannot change after reveal
        assertThatThrownBy(() -> votingService.vote(code, ana, "13"))
                .isInstanceOf(ApiException.class).hasMessageContaining("open");

        // reset clears the round for a re-vote (FR-22)
        votingService.reset(code, facilitator);
        snapshot = sessionService.getSnapshot(code, ana);
        assertThat(snapshot.activeRound().state()).isEqualTo(RoundState.OPEN);
        assertThat(snapshot.participants()).noneMatch(ParticipantDto::hasVoted);

        votingService.vote(code, ana, "5");
        votingService.reveal(code, facilitator);
        // the recorded estimate need not equal any cast vote (FR-23)
        votingService.estimate(code, story.id(), "6", facilitator);
        snapshot = sessionService.getSnapshot(code, ana);
        assertThat(snapshot.stories().get(0).status()).isEqualTo(StoryStatus.ESTIMATED);
        assertThat(snapshot.stories().get(0).finalEstimate()).isEqualTo("6");

        // export (FR-28), facilitator only
        assertThatThrownBy(() -> exportService.exportCsv(code, ana))
                .isInstanceOf(ApiException.class);
        String csv = exportService.exportCsv(code, facilitator);
        assertThat(csv).contains("Login page").contains("Ana").contains("6");
        Map<String, Object> json = exportService.exportJson(code, facilitator);
        assertThat(json.get("sessionCode")).isEqualTo(code);
        assertThat((List<?>) json.get("stories")).hasSize(1);
    }

    @Test
    void voteChangeCanBeDisabled() {
        CreateSessionResponse created = createSession(false, false);
        String code = created.session().code();
        AuthPrincipal facilitator = principal(created.participant().id(), code);
        JoinResponse v1 = sessionService.join(code, new JoinRequest("Ana", null), null);
        AuthPrincipal ana = principal(v1.participant().id(), code);

        StoryDto story = storyService.add(code, new StoryRequest("S", null, null), facilitator);
        votingService.selectStory(code, story.id(), facilitator);
        votingService.vote(code, ana, "5");
        assertThatThrownBy(() -> votingService.vote(code, ana, "8"))
                .isInstanceOf(ApiException.class).hasMessageContaining("disabled");
    }

    @Test
    void kickAndFacilitatorTransfer() {
        CreateSessionResponse created = createSession(false, true);
        String code = created.session().code();
        AuthPrincipal facilitator = principal(created.participant().id(), code);
        JoinResponse v1 = sessionService.join(code, new JoinRequest("Ana", null), null);
        JoinResponse v2 = sessionService.join(code, new JoinRequest("Ben", null), null);
        AuthPrincipal ana = principal(v1.participant().id(), code);

        // non-facilitator cannot kick someone else
        assertThatThrownBy(() -> sessionService.leaveOrKick(code, v2.participant().id(), ana))
                .isInstanceOf(ApiException.class);
        // facilitator kicks Ben (FR-9)
        sessionService.leaveOrKick(code, v2.participant().id(), facilitator);
        SessionSnapshot snapshot = sessionService.getSnapshot(code, facilitator);
        assertThat(snapshot.participants()).extracting(ParticipantDto::displayName)
                .containsExactly("Fay", "Ana");

        // kicked participant loses membership entirely
        AuthPrincipal ben = principal(v2.participant().id(), code);
        assertThatThrownBy(() -> sessionService.getSnapshot(code, ben))
                .isInstanceOf(ApiException.class);

        // transfer facilitation to Ana (FR-5)
        sessionService.changeRole(code, v1.participant().id(), "FACILITATOR", facilitator);
        snapshot = sessionService.getSnapshot(code, ana);
        assertThat(snapshot.facilitatorParticipantId()).isEqualTo(v1.participant().id());
        // old facilitator was demoted and can no longer close the session
        assertThatThrownBy(() -> sessionService.close(code, facilitator))
                .isInstanceOf(ApiException.class);
        sessionService.close(code, ana);
        assertThat(sessionService.requireSession(code).getStatus()).isEqualTo(SessionStatus.CLOSED);
    }

    @Test
    void facilitatorLeavingPromotesOldestVoter() {
        CreateSessionResponse created = createSession(false, true);
        String code = created.session().code();
        AuthPrincipal facilitator = principal(created.participant().id(), code);
        JoinResponse v1 = sessionService.join(code, new JoinRequest("Ana", null), null);
        sessionService.join(code, new JoinRequest("Ben", null), null);

        sessionService.leaveOrKick(code, created.participant().id(), facilitator);
        SessionSnapshot snapshot = sessionService.getSnapshot(code, principal(v1.participant().id(), code));
        assertThat(snapshot.facilitatorParticipantId()).isEqualTo(v1.participant().id());
        assertThat(snapshot.participants().get(0).role()).isEqualTo(Role.FACILITATOR);
    }

    @Test
    void observersCanBeDisallowed() {
        CreateSessionResponse created = sessionService.create(new CreateSessionRequest(
                "No obs", "Fay", FIBONACCI, null, null, false, false, true), null, null);
        assertThatThrownBy(() -> sessionService.join(created.session().code(),
                new JoinRequest("Olly", "OBSERVER"), null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void customDeckSessionsWork() {
        CreateSessionResponse created = sessionService.create(new CreateSessionRequest(
                        "Custom", "Fay", null, List.of("1", "2", "banana"), "My deck", false, true, true),
                null, null);
        assertThat(created.session().deck().values()).containsExactly("1", "2", "banana");
    }

    @Test
    void storyImportCreatesOneStoryPerLine() {
        CreateSessionResponse created = createSession(false, true);
        String code = created.session().code();
        AuthPrincipal facilitator = principal(created.participant().id(), code);
        List<StoryDto> imported = storyService.importFromText(code, "Story A\n\n  Story B  \nStory C", facilitator);
        assertThat(imported).extracting(StoryDto::title).containsExactly("Story A", "Story B", "Story C");
    }
}
