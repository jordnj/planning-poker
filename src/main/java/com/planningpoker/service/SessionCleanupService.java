package com.planningpoker.service;

import com.planningpoker.config.AppProperties;
import com.planningpoker.domain.Session;
import com.planningpoker.domain.SessionStatus;
import com.planningpoker.dto.Dtos.WsEvent;
import com.planningpoker.repo.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Closes sessions with no activity beyond the configured idle timeout (FR-4). */
@Service
public class SessionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(SessionCleanupService.class);

    private final SessionRepository sessions;
    private final Broadcaster broadcaster;
    private final AppProperties props;

    public SessionCleanupService(SessionRepository sessions, Broadcaster broadcaster, AppProperties props) {
        this.sessions = sessions;
        this.broadcaster = broadcaster;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${app.session.cleanup-interval-ms}")
    @Transactional
    public void closeIdleSessions() {
        Instant cutoff = Instant.now().minus(props.session().idleTimeout());
        List<Session> idle = sessions.findByStatusAndLastActivityAtBefore(SessionStatus.ACTIVE, cutoff);
        for (Session session : idle) {
            session.setStatus(SessionStatus.CLOSED);
            broadcaster.toSession(session.getCode(), WsEvent.SESSION_CLOSED, null);
            // log the opaque id, never the code: codes are the capability to join
            log.info("Closed idle session id={}", session.getId());
        }
    }
}
