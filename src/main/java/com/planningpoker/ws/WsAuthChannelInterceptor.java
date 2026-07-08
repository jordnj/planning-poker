package com.planningpoker.ws;

import com.planningpoker.repo.ParticipantRepository;
import com.planningpoker.security.AuthPrincipal;
import com.planningpoker.security.JwtService;
import io.jsonwebtoken.JwtException;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Authenticates every STOMP CONNECT (SEC-2) via the same JWT used for REST,
 * and authorizes SUBSCRIBEs to session topics against current membership
 * (SEC-3): a client may only subscribe to sessions it belongs to.
 */
@Component
public class WsAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;
    private final ParticipantRepository participants;

    public WsAuthChannelInterceptor(JwtService jwtService, ParticipantRepository participants) {
        this.jwtService = jwtService;
        this.participants = participants;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String header = accessor.getFirstNativeHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ")) {
                throw new IllegalArgumentException("Missing Authorization header on STOMP CONNECT");
            }
            AuthPrincipal principal;
            try {
                principal = jwtService.parse(header.substring(7));
            } catch (JwtException | IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid token");
            }
            accessor.setUser(new UsernamePasswordAuthenticationToken(
                    principal, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + principal.type().name()))));
        }

        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            String destination = accessor.getDestination();
            AuthPrincipal principal = WsSupport.principalOf(accessor.getUser());
            if (destination != null && destination.startsWith("/topic/sessions/")) {
                String code = destination.substring("/topic/sessions/".length());
                if (principal == null || !principal.isParticipant()
                        || !code.equals(principal.sessionCode())
                        || !participants.existsByIdAndActiveTrueAndSession_Code(principal.id(), code)) {
                    throw new IllegalArgumentException("Not a member of session " + code);
                }
            } else if (destination != null && !destination.startsWith("/user/")) {
                throw new IllegalArgumentException("Subscription not allowed: " + destination);
            }
        }

        return message;
    }
}
