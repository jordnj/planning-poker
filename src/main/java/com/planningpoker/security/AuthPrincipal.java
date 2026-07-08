package com.planningpoker.security;

import java.security.Principal;
import java.util.UUID;

/**
 * Authenticated identity carried by a JWT. Two kinds exist:
 * PARTICIPANT tokens are scoped to a single session (guests and members),
 * USER tokens identify a registered account.
 *
 * Roles are deliberately NOT trusted from the token: authorization always
 * re-reads the participant's current role from the database (RT-1 / SEC-3).
 */
public record AuthPrincipal(
        TokenType type,
        UUID id,
        String sessionCode,
        String displayName
) implements Principal {

    public enum TokenType {USER, PARTICIPANT}

    @Override
    public String getName() {
        return id.toString();
    }

    public boolean isParticipant() {
        return type == TokenType.PARTICIPANT;
    }

    public boolean isUser() {
        return type == TokenType.USER;
    }
}
