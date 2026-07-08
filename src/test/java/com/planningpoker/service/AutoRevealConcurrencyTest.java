package com.planningpoker.service;

import com.planningpoker.domain.RoundState;
import com.planningpoker.dto.Dtos.*;
import com.planningpoker.security.AuthPrincipal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * TST-5: simultaneous votes racing the auto-reveal threshold must produce
 * exactly one reveal with all votes intact.
 */
@SpringBootTest
class AutoRevealConcurrencyTest {

    private static final UUID FIBONACCI = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final int VOTERS = 12;

    @Autowired
    SessionService sessionService;
    @Autowired
    StoryService storyService;
    @Autowired
    VotingService votingService;

    @MockitoSpyBean
    Broadcaster broadcaster;

    @Test
    void simultaneousLastVotesRevealExactlyOnce() throws Exception {
        CreateSessionResponse created = sessionService.create(new CreateSessionRequest(
                        "Race", "Fay", FIBONACCI, null, null, true, true, true),
                null, null);
        String code = created.session().code();
        AuthPrincipal facilitator = new AuthPrincipal(
                AuthPrincipal.TokenType.PARTICIPANT, created.participant().id(), code, "Fay");

        List<AuthPrincipal> voters = new ArrayList<>();
        for (int i = 0; i < VOTERS; i++) {
            JoinResponse join = sessionService.join(code, new JoinRequest("V" + i, "VOTER"), null);
            voters.add(new AuthPrincipal(AuthPrincipal.TokenType.PARTICIPANT,
                    join.participant().id(), code, "V" + i));
        }

        StoryDto story = storyService.add(code, new StoryRequest("Race story", null, null), facilitator);
        votingService.selectStory(code, story.id(), facilitator);

        ExecutorService pool = Executors.newFixedThreadPool(VOTERS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(VOTERS);
        for (AuthPrincipal voter : voters) {
            pool.submit(() -> {
                try {
                    start.await();
                    votingService.vote(code, voter, "5");
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        SessionSnapshot snapshot = sessionService.getSnapshot(code, facilitator);
        assertThat(snapshot.activeRound().state()).isEqualTo(RoundState.REVEALED);
        assertThat(snapshot.activeRound().votes()).hasSize(VOTERS);
        assertThat(snapshot.activeRound().stats().consensus()).isTrue();

        // exactly one reveal broadcast despite the race
        verify(broadcaster, times(1)).toSession(
                ArgumentMatchers.eq(code),
                ArgumentMatchers.eq(WsEvent.ROUND_REVEALED),
                ArgumentMatchers.any());
    }
}
