package com.planningpoker.ws;

import com.planningpoker.security.AuthPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.security.Principal;

final class WsSupport {

    private WsSupport() {
    }

    static AuthPrincipal principalOf(Principal user) {
        if (user instanceof UsernamePasswordAuthenticationToken token
                && token.getPrincipal() instanceof AuthPrincipal principal) {
            return principal;
        }
        return null;
    }
}
