package com.planningpoker.web;

import com.planningpoker.dto.Dtos.*;
import com.planningpoker.security.AuthPrincipal;
import com.planningpoker.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateSessionResponse create(@Valid @RequestBody CreateSessionRequest req,
                                        Authentication authentication,
                                        HttpServletRequest request) {
        return sessionService.create(req, principalOf(authentication), originOf(request));
    }

    @GetMapping("/{code}")
    public SessionSnapshot get(@PathVariable String code, Authentication authentication) {
        return sessionService.getSnapshot(code, principalOf(authentication));
    }

    @PostMapping("/{code}/close")
    public void close(@PathVariable String code, Authentication authentication) {
        sessionService.close(code, principalOf(authentication));
    }

    @PostMapping("/{code}/participants")
    @ResponseStatus(HttpStatus.CREATED)
    public JoinResponse join(@PathVariable String code,
                             @Valid @RequestBody JoinRequest req,
                             Authentication authentication) {
        return sessionService.join(code, req, principalOf(authentication));
    }

    @DeleteMapping("/{code}/participants/{participantId}")
    public void leaveOrKick(@PathVariable String code,
                            @PathVariable UUID participantId,
                            Authentication authentication) {
        sessionService.leaveOrKick(code, participantId, principalOf(authentication));
    }

    @PutMapping("/{code}/participants/{participantId}/role")
    public void changeRole(@PathVariable String code,
                           @PathVariable UUID participantId,
                           @Valid @RequestBody RoleChangeRequest req,
                           Authentication authentication) {
        sessionService.changeRole(code, participantId, req.role(), principalOf(authentication));
    }

    static AuthPrincipal principalOf(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof AuthPrincipal p ? p : null;
    }

    private String originOf(HttpServletRequest request) {
        String scheme = request.getHeader("X-Forwarded-Proto") != null
                ? request.getHeader("X-Forwarded-Proto") : request.getScheme();
        String host = request.getHeader("X-Forwarded-Host") != null
                ? request.getHeader("X-Forwarded-Host") : request.getHeader("Host");
        return host == null ? null : scheme + "://" + host;
    }
}
