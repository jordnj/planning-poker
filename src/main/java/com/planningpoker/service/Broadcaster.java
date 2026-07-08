package com.planningpoker.service;

import com.planningpoker.dto.ApiError;
import com.planningpoker.dto.Dtos.WsEvent;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

/**
 * Fans session events out to all subscribed clients (§9). With the simple
 * broker this stays in-process; pointing the STOMP relay at RabbitMQ/Redis
 * (RT-2) requires no change here.
 */
@Service
public class Broadcaster {

    private final SimpMessagingTemplate template;

    public Broadcaster(SimpMessagingTemplate template) {
        this.template = template;
    }

    public void toSession(String sessionCode, String type, Object payload) {
        template.convertAndSend("/topic/sessions/" + sessionCode, new WsEvent(type, payload));
    }

    public void errorToUser(Principal user, int status, String code, String message) {
        template.convertAndSendToUser(user.getName(), "/queue/errors",
                new ApiError(Instant.now(), status, code, message, UUID.randomUUID().toString()));
    }

    public void snapshotToUser(String userName, Object snapshot) {
        template.convertAndSendToUser(userName, "/queue/snapshot", snapshot);
    }
}
