package com.planningpoker.crypto;

import com.planningpoker.dto.Dtos.*;
import com.planningpoker.security.AuthPrincipal;
import com.planningpoker.service.SessionService;
import com.planningpoker.service.StoryService;
import com.planningpoker.service.VotingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guarantee the user actually cares about: read the tables the way a
 * database administrator would and confirm nothing readable is there.
 *
 * Two mechanisms are under test at once:
 *   * end-to-end fields (story text, display names) — the browser encrypts
 *     these, so this test plays the client's part by submitting ciphertext and
 *     asserting the server neither altered it nor could read it.
 *   * at-rest fields (session name, vote values, final estimate) — submitted in
 *     the clear and encrypted by the converter on the way to the column.
 */
@SpringBootTest
class DatabaseHoldsNoPlaintextTest {

    private static final UUID FIBONACCI = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    SessionService sessionService;
    @Autowired
    StoryService storyService;
    @Autowired
    VotingService votingService;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void noSensitiveValueIsStoredInTheClear() {
        // stand-ins for what the browser would send: opaque blobs
        String encName = "e1.Y2lwaGVydGV4dC1mYWNpbGl0YXRvcg";
        String encVoterName = "e1.Y2lwaGVydGV4dC12b3Rlcg";
        String encTitle = "e1.Y2lwaGVydGV4dC10aXRsZQ";
        String encDescription = "e1.Y2lwaGVydGV4dC1kZXNjcmlwdGlvbg";
        String encUrl = "e1.Y2lwaGVydGV4dC11cmw";

        CreateSessionResponse created = sessionService.create(new CreateSessionRequest(
                "Acquisition planning", encName, FIBONACCI, null, null, false, true, true),
                null, null);
        String code = created.session().code();
        UUID sessionId = jdbc.queryForObject(
                "select id from sessions where code = ?", UUID.class, code);
        AuthPrincipal facilitator = new AuthPrincipal(
                AuthPrincipal.TokenType.PARTICIPANT, created.participant().id(), code, "x");

        JoinResponse voterJoin = sessionService.join(code, new JoinRequest(encVoterName, "VOTER"), null);
        AuthPrincipal voter = new AuthPrincipal(
                AuthPrincipal.TokenType.PARTICIPANT, voterJoin.participant().id(), code, "x");

        StoryDto story = storyService.add(code, new StoryRequest(encTitle, encDescription, encUrl), facilitator);
        votingService.selectStory(code, story.id(), facilitator);
        votingService.vote(code, voter, "13");
        votingService.reveal(code, facilitator);
        votingService.estimate(code, story.id(), "21", facilitator);

        // --- at rest: encrypted by the server, unreadable in the column ---
        String storedSessionName = jdbc.queryForObject(
                "select name from sessions where id = ?", String.class, sessionId);
        assertThat(storedSessionName).startsWith("v1:").doesNotContain("Acquisition");

        String storedCardValue = jdbc.queryForObject(
                "select v.card_value from votes v join rounds r on v.round_id = r.id "
                        + "join stories s on r.story_id = s.id where s.session_id = ?",
                String.class, sessionId);
        assertThat(storedCardValue).startsWith("v1:").isNotEqualTo("13");

        String storedEstimate = jdbc.queryForObject(
                "select final_estimate from stories where id = ?", String.class, story.id());
        assertThat(storedEstimate).startsWith("v1:").isNotEqualTo("21");

        // --- end to end: stored exactly as the client sent it, never decrypted ---
        var storedStory = jdbc.queryForMap(
                "select title, description, reference_url from stories where id = ?", story.id());
        assertThat(storedStory.get("title")).isEqualTo(encTitle);
        assertThat(storedStory.get("description")).isEqualTo(encDescription);
        assertThat(storedStory.get("reference_url")).isEqualTo(encUrl);

        List<String> storedNames = jdbc.queryForList(
                "select display_name from participants where session_id = ?", String.class, sessionId);
        assertThat(storedNames).containsExactlyInAnyOrder(encName, encVoterName);

        // and the whole row set, as a DBA would dump it, contains no plaintext
        String dump = jdbc.queryForList(
                        "select name from sessions where id = ?", String.class, sessionId).toString()
                + storedNames + storedStory.values() + storedCardValue + storedEstimate;
        assertThat(dump)
                .doesNotContain("Acquisition planning")
                .doesNotContain("ciphertext-title");
    }

    @Test
    void roundTripStillReadsCorrectlyThroughTheApplication() {
        // encryption must be transparent to the app: what went in comes back out
        CreateSessionResponse created = sessionService.create(new CreateSessionRequest(
                "Readback check", "e1.bmFtZQ", FIBONACCI, null, null, false, true, true),
                null, null);
        String code = created.session().code();
        AuthPrincipal facilitator = new AuthPrincipal(
                AuthPrincipal.TokenType.PARTICIPANT, created.participant().id(), code, "x");
        JoinResponse voterJoin = sessionService.join(code, new JoinRequest("e1.dm90ZXI", "VOTER"), null);
        AuthPrincipal voter = new AuthPrincipal(
                AuthPrincipal.TokenType.PARTICIPANT, voterJoin.participant().id(), code, "x");

        StoryDto story = storyService.add(code, new StoryRequest("e1.dGl0bGU", null, null), facilitator);
        votingService.selectStory(code, story.id(), facilitator);
        votingService.vote(code, voter, "8");
        votingService.reveal(code, facilitator);

        SessionSnapshot snapshot = sessionService.getSnapshot(code, facilitator);
        assertThat(snapshot.name()).isEqualTo("Readback check");
        assertThat(snapshot.activeStory().title()).isEqualTo("e1.dGl0bGU");
        assertThat(snapshot.activeRound().votes()).singleElement()
                .satisfies(v -> assertThat(v.cardValue()).isEqualTo("8"));
        // statistics still work, because the server sees card values in the clear
        assertThat(snapshot.activeRound().stats().average()).isEqualTo(8.0);
    }
}
