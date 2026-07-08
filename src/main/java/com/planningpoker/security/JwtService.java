package com.planningpoker.security;

import com.planningpoker.config.AppProperties;
import com.planningpoker.domain.Participant;
import com.planningpoker.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private final AppProperties props;

    public JwtService(AppProperties props) {
        this.props = props;
        byte[] bytes = props.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("app.jwt-secret must be at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    /** Signed, session-scoped participant token (SEC-1). */
    public String issueParticipantToken(Participant participant, String sessionCode) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(participant.getId().toString())
                .claim("typ", AuthPrincipal.TokenType.PARTICIPANT.name())
                .claim("session", sessionCode)
                .claim("name", participant.getDisplayName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(props.token().participantTtl())))
                .signWith(key)
                .compact();
    }

    public String issueUserToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("typ", AuthPrincipal.TokenType.USER.name())
                .claim("name", user.getUsername())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(props.token().userTtl())))
                .signWith(key)
                .compact();
    }

    /** @throws io.jsonwebtoken.JwtException on invalid/expired tokens */
    public AuthPrincipal parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token)
                .getPayload();
        AuthPrincipal.TokenType type = AuthPrincipal.TokenType.valueOf(claims.get("typ", String.class));
        return new AuthPrincipal(
                type,
                UUID.fromString(claims.getSubject()),
                claims.get("session", String.class),
                claims.get("name", String.class)
        );
    }
}
