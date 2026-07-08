package com.planningpoker.ws;

import com.planningpoker.dto.Dtos.*;
import com.planningpoker.service.SessionService;
import com.planningpoker.service.StoryService;
import com.planningpoker.service.VotingService;
import com.planningpoker.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TST-3 / TST-4: STOMP fan-out across multiple clients — vote produces a
 * value-free VOTE_STATUS broadcast, reveal delivers all values to everyone,
 * and unauthenticated CONNECTs are rejected (SEC-2).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StompIntegrationTest {

    private static final UUID FIBONACCI = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @LocalServerPort
    int port;

    @Autowired
    SessionService sessionService;
    @Autowired
    StoryService storyService;
    @Autowired
    VotingService votingService;

    private WebSocketStompClient newClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        return client;
    }

    private String url() {
        // raw WebSocket transport of the SockJS endpoint
        return "ws://localhost:" + port + "/ws/websocket";
    }

    private StompSession facilitatorSession;
    private StompSession voterSession;

    @AfterEach
    void tearDown() {
        if (facilitatorSession != null && facilitatorSession.isConnected()) {
            facilitatorSession.disconnect();
        }
        if (voterSession != null && voterSession.isConnected()) {
            voterSession.disconnect();
        }
    }

    record TestEvent(String type, Object payload) {
    }

    private StompSession connect(String token, BlockingQueue<TestEvent> events, String code) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);
        StompSession session = newClient()
                .connectAsync(url(), new WebSocketHttpHeaders(), connectHeaders,
                        new StompSessionHandlerAdapter() {
                        })
                .get(10, TimeUnit.SECONDS);
        session.subscribe("/topic/sessions/" + code, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                Map<String, Object> map = (Map<String, Object>) payload;
                events.add(new TestEvent((String) map.get("type"), map.get("payload")));
            }
        });
        return session;
    }

    private TestEvent awaitEvent(BlockingQueue<TestEvent> queue, String type) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            TestEvent event = queue.poll(500, TimeUnit.MILLISECONDS);
            if (event != null && event.type().equals(type)) {
                return event;
            }
        }
        throw new AssertionError("Timed out waiting for event " + type);
    }

    @Test
    @SuppressWarnings("unchecked")
    void voteHiddenThenRevealFansOutToAllClients() throws Exception {
        CreateSessionResponse created = sessionService.create(new CreateSessionRequest(
                "WS", "Fay", FIBONACCI, null, null, false, true, true), null, null);
        String code = created.session().code();
        JoinResponse voter = sessionService.join(code, new JoinRequest("Ana", "VOTER"), null);
        AuthPrincipal facilitatorPrincipal = new AuthPrincipal(
                AuthPrincipal.TokenType.PARTICIPANT, created.participant().id(), code, "Fay");

        StoryDto story = storyService.add(code, new StoryRequest("WS story", null, null), facilitatorPrincipal);

        BlockingQueue<TestEvent> facilitatorEvents = new LinkedBlockingQueue<>();
        BlockingQueue<TestEvent> voterEvents = new LinkedBlockingQueue<>();
        facilitatorSession = connect(created.token(), facilitatorEvents, code);
        voterSession = connect(voter.token(), voterEvents, code);

        // facilitator selects the story over STOMP
        facilitatorSession.send("/app/sessions/" + code + "/story/select",
                Map.of("storyId", story.id().toString()));
        awaitEvent(facilitatorEvents, "STORY_SELECTED");
        awaitEvent(voterEvents, "STORY_SELECTED");

        // voter votes over STOMP; both clients see WHO voted but not WHAT (RT-4)
        voterSession.send("/app/sessions/" + code + "/vote", Map.of("cardValue", "5"));
        TestEvent status = awaitEvent(facilitatorEvents, "VOTE_STATUS");
        Map<String, Object> statusPayload = (Map<String, Object>) status.payload();
        assertThat(statusPayload.get("participantId")).isEqualTo(voter.participant().id().toString());
        assertThat(statusPayload.get("hasVoted")).isEqualTo(true);
        assertThat(statusPayload).doesNotContainKey("cardValue");
        awaitEvent(voterEvents, "VOTE_STATUS");

        // facilitator reveals; everyone receives all values + stats simultaneously (FR-20/21)
        facilitatorSession.send("/app/sessions/" + code + "/reveal", Map.of());
        for (BlockingQueue<TestEvent> queue : List.of(facilitatorEvents, voterEvents)) {
            TestEvent revealed = awaitEvent(queue, "ROUND_REVEALED");
            Map<String, Object> payload = (Map<String, Object>) revealed.payload();
            List<Map<String, Object>> votes = (List<Map<String, Object>>) payload.get("votes");
            assertThat(votes).hasSize(1);
            assertThat(votes.get(0).get("cardValue")).isEqualTo("5");
            Map<String, Object> stats = (Map<String, Object>) payload.get("stats");
            assertThat(stats.get("consensus")).isEqualTo(true);
        }

        // a voter attempting a facilitator action gets an error on the user queue, not a broadcast
        BlockingQueue<Object> errors = new LinkedBlockingQueue<>();
        voterSession.subscribe("/user/queue/errors", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                errors.add(payload);
            }
        });
        voterSession.send("/app/sessions/" + code + "/reset", Map.of());
        Object error = errors.poll(10, TimeUnit.SECONDS);
        assertThat(error).isNotNull();
        assertThat(((Map<String, Object>) error).get("status")).isEqualTo(403);
    }

    @Test
    void connectWithoutTokenIsRejected() {
        BlockingQueue<TestEvent> events = new LinkedBlockingQueue<>();
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () ->
                newClient().connectAsync(url(), new WebSocketHttpHeaders(), new StompHeaders(),
                                new StompSessionHandlerAdapter() {
                                })
                        .get(10, TimeUnit.SECONDS));
        assertThat(events).isEmpty();
    }

    @Test
    void subscribingToAForeignSessionIsRejected() throws Exception {
        CreateSessionResponse one = sessionService.create(new CreateSessionRequest(
                "One", "Fay", FIBONACCI, null, null, false, true, true), null, null);
        CreateSessionResponse two = sessionService.create(new CreateSessionRequest(
                "Two", "Gil", FIBONACCI, null, null, false, true, true), null, null);

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + one.token());
        StompSession session = newClient()
                .connectAsync(url(), new WebSocketHttpHeaders(), connectHeaders,
                        new StompSessionHandlerAdapter() {
                        })
                .get(10, TimeUnit.SECONDS);

        BlockingQueue<TestEvent> foreignEvents = new LinkedBlockingQueue<>();
        try {
            // subscription to a session this participant does not belong to must not deliver events
            session.subscribe("/topic/sessions/" + two.session().code(), new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return Map.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    foreignEvents.add(new TestEvent("event", payload));
                }
            });
        } catch (Exception expected) {
            // subscribe may fail fast; either way no events must arrive
        }

        AuthPrincipal gil = new AuthPrincipal(AuthPrincipal.TokenType.PARTICIPANT,
                two.participant().id(), two.session().code(), "Gil");
        storyService.add(two.session().code(), new StoryRequest("Secret", null, null), gil);

        assertThat(foreignEvents.poll(3, TimeUnit.SECONDS)).isNull();
        if (session.isConnected()) {
            session.disconnect();
        }
    }
}
