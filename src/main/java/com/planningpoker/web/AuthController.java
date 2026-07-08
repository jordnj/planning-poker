package com.planningpoker.web;

import com.planningpoker.domain.User;
import com.planningpoker.dto.Dtos.AuthResponse;
import com.planningpoker.dto.Dtos.LoginRequest;
import com.planningpoker.dto.Dtos.RegisterRequest;
import com.planningpoker.repo.UserRepository;
import com.planningpoker.security.JwtService;
import jakarta.validation.Valid;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest req) {
        if (users.existsByUsername(req.username().trim())) {
            throw ApiException.conflict("USERNAME_TAKEN", "Username is already taken");
        }
        User user = new User();
        user.setUsername(req.username().trim());
        user.setEmail(req.email());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setProvider("LOCAL");
        user = users.save(user);
        return new AuthResponse(jwtService.issueUserToken(user), user.getId(), user.getUsername());
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        User user = users.findByUsername(req.username().trim())
                .filter(u -> passwordEncoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> ApiException.unauthorized("Invalid username or password"));
        return new AuthResponse(jwtService.issueUserToken(user), user.getId(), user.getUsername());
    }
}
