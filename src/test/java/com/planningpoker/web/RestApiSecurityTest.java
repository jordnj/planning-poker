package com.planningpoker.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** HTTP-level auth: guest flows are open, member/facilitator APIs need tokens (SEC-1..SEC-3). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RestApiSecurityTest {

    private static final String FIBONACCI = "00000000-0000-0000-0000-000000000001";

    @Autowired
    TestRestTemplate rest;

    @SuppressWarnings("unchecked")
    @Test
    void guestCreateJoinAndMemberOnlySnapshot() {
        // anonymous create (creator becomes facilitator, gets a participant token)
        Map<String, Object> createBody = Map.of(
                "name", "Sprint X",
                "facilitatorName", "Fay",
                "deckId", FIBONACCI);
        ResponseEntity<Map> created = rest.postForEntity("/api/sessions", createBody, Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String token = (String) created.getBody().get("token");
        String code = (String) ((Map<String, Object>) created.getBody().get("session")).get("code");
        assertThat(token).isNotBlank();
        assertThat(code).hasSize(8);

        // snapshot without a token → 403 (Spring Security denies anonymous)
        ResponseEntity<Map> anonymous = rest.getForEntity("/api/sessions/" + code, Map.class);
        assertThat(anonymous.getStatusCode().value()).isIn(401, 403);

        // guest join → participant token
        ResponseEntity<Map> joined = rest.postForEntity("/api/sessions/" + code + "/participants",
                Map.of("displayName", "Ana"), Map.class);
        assertThat(joined.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String voterToken = (String) joined.getBody().get("token");

        // member snapshot with token works
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(voterToken);
        ResponseEntity<Map> snapshot = rest.exchange("/api/sessions/" + code, HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        assertThat(snapshot.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(snapshot.getBody().get("code")).isEqualTo(code);

        // a participant token from session A cannot read session B
        ResponseEntity<Map> other = rest.postForEntity("/api/sessions",
                Map.of("name", "Other", "facilitatorName", "Gil", "deckId", FIBONACCI), Map.class);
        String otherCode = (String) ((Map<String, Object>) other.getBody().get("session")).get("code");
        ResponseEntity<Map> crossSession = rest.exchange("/api/sessions/" + otherCode, HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        assertThat(crossSession.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // voter cannot use facilitator endpoints (story add)
        ResponseEntity<Map> storyDenied = rest.exchange("/api/sessions/" + code + "/stories",
                HttpMethod.POST, new HttpEntity<>(Map.of("title", "Nope"), headers), Map.class);
        assertThat(storyDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // facilitator can
        HttpHeaders facHeaders = new HttpHeaders();
        facHeaders.setBearerAuth(token);
        ResponseEntity<Map> storyOk = rest.exchange("/api/sessions/" + code + "/stories",
                HttpMethod.POST, new HttpEntity<>(Map.of("title", "Login page"), facHeaders), Map.class);
        assertThat(storyOk.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // error body shape (§8): unknown session yields the consistent error envelope
        ResponseEntity<Map> notFound = rest.exchange("/api/sessions/ZZZZZZZZ", HttpMethod.GET,
                new HttpEntity<>(facHeaders), Map.class);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map<String, Object> body = notFound.getBody();
        assertThat(body).containsKeys("timestamp", "status", "code", "message", "traceId");
    }

    @Test
    void registerLoginAndCustomDecks() {
        Map<String, Object> register = Map.of(
                "username", "alice", "email", "alice@example.com", "password", "s3cret-pass");
        ResponseEntity<Map> registered = rest.postForEntity("/api/auth/register", register, Map.class);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.OK);
        String userToken = (String) registered.getBody().get("token");

        // login works
        ResponseEntity<Map> login = rest.postForEntity("/api/auth/login",
                Map.of("username", "alice", "password", "s3cret-pass"), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);

        // wrong password rejected
        ResponseEntity<Map> bad = rest.postForEntity("/api/auth/login",
                Map.of("username", "alice", "password", "wrong"), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // guests cannot save decks, users can (FR-25)
        ResponseEntity<Map> deckDenied = rest.postForEntity("/api/decks",
                Map.of("name", "Mine", "values", java.util.List.of("1", "2")), Map.class);
        assertThat(deckDenied.getStatusCode().value()).isIn(401, 403);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userToken);
        ResponseEntity<Map> deckOk = rest.exchange("/api/decks", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Mine", "values", java.util.List.of("1", "2")), headers), Map.class);
        assertThat(deckOk.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // deck listing includes the 5 built-ins (FR-24)
        ResponseEntity<java.util.List> decks = rest.getForEntity("/api/decks", java.util.List.class);
        assertThat(decks.getBody().size()).isGreaterThanOrEqualTo(5);
    }
}
