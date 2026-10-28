package com.planningpoker.config;

import com.planningpoker.dto.Dtos.CreateSessionRequest;
import com.planningpoker.dto.Dtos.CreateSessionResponse;
import com.planningpoker.service.SessionService;
import com.planningpoker.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Data-minimization behaviour: no addresses retained, no codes in messages. */
@SpringBootTest
class PrivacyHardeningTest {

    private static final UUID FIBONACCI = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    SessionService sessionService;

    @Test
    void rateLimitBucketsCannotBeReadBackAsAddresses() {
        AppProperties props = new AppProperties("x".repeat(32), null, null,
                new AppProperties.Session(Duration.ofHours(4), 300_000),
                new AppProperties.Token(Duration.ofHours(1), Duration.ofHours(1)),
                new AppProperties.RateLimit(20, 60),
                new AppProperties.Actuator("actuator", ""));
        RateLimitFilter filter = new RateLimitFilter(props);

        String bucket = filter.pseudonym("203.0.113.47");
        assertThat(bucket).doesNotContain("203").doesNotContain("113").doesNotContain("47");
        // stable within the process so limiting still works...
        assertThat(filter.pseudonym("203.0.113.47")).isEqualTo(bucket);
        // ...and distinct per address
        assertThat(filter.pseudonym("203.0.113.48")).isNotEqualTo(bucket);

        // a second process (new salt) derives a different value for the same
        // address, so buckets cannot be correlated across restarts
        assertThat(new RateLimitFilter(props).pseudonym("203.0.113.47")).isNotEqualTo(bucket);
    }

    @Test
    void errorsForUnknownSessionsDoNotEchoTheCode() {
        // the code is the capability to join, so it must not travel into logs
        // or error bodies where it could be harvested
        assertThatThrownBy(() -> sessionService.requireSession("SECRET99"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Session not found");
    }

    @Test
    void sessionCodesAreHighEntropyAndNonSequential() {
        CreateSessionResponse first = create();
        CreateSessionResponse second = create();
        assertThat(first.session().code()).hasSize(8).isNotEqualTo(second.session().code());
        // drawn from the unambiguous alphabet, never lowercase or 0/O/1/I/L
        assertThat(first.session().code()).matches("[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{8}");
    }

    private CreateSessionResponse create() {
        return sessionService.create(new CreateSessionRequest(
                "n", "e1.bmFtZQ", FIBONACCI, null, null, false, true, true), null, null);
    }
}
