package com.planningpoker.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Actuator exposure. Before the dedicated actuator filter chain, the API
 * chain's `.anyRequest().authenticated()` meant ANY guest participant token
 * read /actuator/metrics — verified returning 200. These tests pin it shut.
 */
class ActuatorAccessTest {

    private static final String FIBONACCI = "00000000-0000-0000-0000-000000000001";

    /** A guest participant token, exactly what a visitor gets by creating a session. */
    @SuppressWarnings("unchecked")
    private static String guestToken(TestRestTemplate rest) {
        ResponseEntity<Map> created = rest.postForEntity("/api/sessions",
                Map.of("name", "probe", "facilitatorName", "e1.abc", "deckId", FIBONACCI), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) created.getBody().get("token");
    }

    private static HttpEntity<Void> bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    static class ClosedByDefault {

        @Autowired
        TestRestTemplate rest;

        @Test
        void healthAndInfoStayPublicForPlatformProbes() {
            // Cloud Run and friends must be able to probe without credentials
            assertThat(rest.getForEntity("/actuator/health", Map.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        void metricsAreClosedToAnonymousAndToGuestTokens() {
            assertThat(rest.getForEntity("/actuator/metrics", Map.class).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);

            // the regression this chain exists to prevent
            ResponseEntity<Map> withGuest = rest.exchange("/actuator/metrics", HttpMethod.GET,
                    bearer(guestToken(rest)), Map.class);
            assertThat(withGuest.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        void noOperatorAccountExistsWhenNoPasswordIsConfigured() {
            assertThat(rest.withBasicAuth("actuator", "")
                    .getForEntity("/actuator/metrics", Map.class).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "app.actuator.password=operator-s3cret")
    static class WithOperatorCredential {

        @Autowired
        TestRestTemplate rest;

        @Test
        void operatorCanReadMetricsSoNfr15StillHolds() {
            assertThat(rest.withBasicAuth("actuator", "operator-s3cret")
                    .getForEntity("/actuator/metrics", Map.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        void aGuestTokenStillCannotReadMetricsEvenWhenAnOperatorExists() {
            ResponseEntity<Map> withGuest = rest.exchange("/actuator/metrics", HttpMethod.GET,
                    bearer(guestToken(rest)), Map.class);
            assertThat(withGuest.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        void wrongPasswordIsRejected() {
            assertThat(rest.withBasicAuth("actuator", "nope")
                    .getForEntity("/actuator/metrics", Map.class).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
