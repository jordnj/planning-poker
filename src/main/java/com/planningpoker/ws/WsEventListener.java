package com.planningpoker.ws;

import com.planningpoker.domain.ConnectionStatus;
import com.planningpoker.dto.Dtos.SessionSnapshot;
import com.planningpoker.repo.SessionRepository;
import com.planningpoker.security.AuthPrincipal;
import com.planningpoker.service.Broadcaster;
import com.planningpoker.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

/**
 * Connection lifecycle: reflects connect/disconnect in participant state
 * (FR-8, FR-10) and pushes a full snapshot to a client when it subscribes
 * to its session topic, so reconnects resynchronize without a reload (RT-3).
 */
@Component
public class WsEventListener {

    private static final Logger log = LoggerFactory.getLogger(WsEventListener.class);

    private final SessionService sessionService;
    private final SessionRepository sessions;
    private final Broadcaster broadcaster;

    public WsEventListener(SessionService sessionService, SessionRepository sessions, Broadcaster broadcaster) {
        this.sessionService = sessionService;
        this.sessions = sessions;
        this.broadcaster = broadcaster;
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        AuthPrincipal principal = WsSupport.principalOf(event.getUser());
        if (principal != null && principal.isParticipant()) {
            sessionService.connectionChanged(principal.id(), ConnectionStatus.CONNECTED);
        }
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        AuthPrincipal principal = WsSupport.principalOf(event.getUser());
        if (principal != null && principal.isParticipant()) {
            sessionService.connectionChanged(principal.id(), ConnectionStatus.DISCONNECTED);
        }
    }

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        AuthPrincipal principal = WsSupport.principalOf(event.getUser());
        if (principal == null || destination == null || !destination.startsWith("/topic/sessions/")) {
            return;
        }
        String code = destination.substring("/topic/sessions/".length());
        if (sessions.findByCode(code).isEmpty()) {
            return;
        }
        try {
            SessionSnapshot snapshot = sessionService.snapshotByCode(code);
            broadcaster.snapshotToUser(event.getUser().getName(), snapshot);
        } catch (Exception e) {
            log.warn("Failed to send snapshot for {}: {}", code, e.getMessage());
        }
    }
}
