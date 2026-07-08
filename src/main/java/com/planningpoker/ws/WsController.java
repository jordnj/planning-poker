package com.planningpoker.ws;

import com.planningpoker.dto.Dtos.SelectStoryMessage;
import com.planningpoker.dto.Dtos.VoteMessage;
import com.planningpoker.security.AuthPrincipal;
import com.planningpoker.service.Broadcaster;
import com.planningpoker.service.VotingService;
import com.planningpoker.web.ApiException;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * STOMP application destinations (§9). Every handler re-verifies membership
 * and role in the service layer from the database (RT-1); failures are
 * reported on the caller's /user/queue/errors destination.
 */
@Controller
public class WsController {

    private final VotingService votingService;
    private final Broadcaster broadcaster;

    public WsController(VotingService votingService, Broadcaster broadcaster) {
        this.votingService = votingService;
        this.broadcaster = broadcaster;
    }

    @MessageMapping("/sessions/{code}/vote")
    public void vote(@DestinationVariable String code, @Payload VoteMessage message, Principal user) {
        handle(user, () -> votingService.vote(code, require(user), message.cardValue()));
    }

    @MessageMapping("/sessions/{code}/reveal")
    public void reveal(@DestinationVariable String code, Principal user) {
        handle(user, () -> votingService.reveal(code, require(user)));
    }

    @MessageMapping("/sessions/{code}/reset")
    public void reset(@DestinationVariable String code, Principal user) {
        handle(user, () -> votingService.reset(code, require(user)));
    }

    @MessageMapping("/sessions/{code}/story/select")
    public void selectStory(@DestinationVariable String code, @Payload SelectStoryMessage message, Principal user) {
        handle(user, () -> votingService.selectStory(code, message.storyId(), require(user)));
    }

    private AuthPrincipal require(Principal user) {
        AuthPrincipal principal = WsSupport.principalOf(user);
        if (principal == null) {
            throw ApiException.unauthorized("Not authenticated");
        }
        return principal;
    }

    private void handle(Principal user, Runnable action) {
        try {
            action.run();
        } catch (ApiException e) {
            if (user != null) {
                broadcaster.errorToUser(user, e.getStatus().value(), e.getCode(), e.getMessage());
            }
        }
    }
}
