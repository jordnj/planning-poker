package com.planningpoker.config;

import com.planningpoker.security.JwtAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Actuator gets its own chain, ahead of the API chain and deliberately
     * WITHOUT {@link JwtAuthFilter}: a participant or user bearer token must
     * not count as authentication here. Without this, `.anyRequest()
     * .authenticated()` on the API chain let any guest's session token read
     * /actuator/metrics, leaking session counts and JVM internals from an app
     * whose whole point is that the operator learns as little as possible.
     *
     * Health and info stay public for platform probes. Everything else needs
     * an operator credential (APP_ACTUATOR_PASSWORD); with none configured no
     * account exists, so those endpoints are closed by default.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain actuatorFilterChain(HttpSecurity http) throws Exception {
        // Without an explicit entry point this chain falls back to
        // Http403ForbiddenEntryPoint, so an operator gets a bare 403 and no
        // WWW-Authenticate challenge — curl -u and browsers cannot prompt.
        BasicAuthenticationEntryPoint challenge = new BasicAuthenticationEntryPoint();
        challenge.setRealmName("planning-poker");
        challenge.afterPropertiesSet();

        http
                .securityMatcher("/actuator/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().hasRole("ACTUATOR"))
                .httpBasic(basic -> basic.authenticationEntryPoint(challenge))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(challenge));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter) throws Exception {
        http
                // Bearer-token API: CSRF does not apply (SEC-6); no cookies are used for state changes.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers.httpStrictTransportSecurity(hsts -> hsts
                        .includeSubDomains(true).maxAgeInSeconds(31536000)))
                .authorizeHttpRequests(auth -> auth
                        // static SPA assets
                        .requestMatchers("/", "/index.html", "/css/**", "/js/**", "/webjars/**", "/favicon.ico").permitAll()
                        // The container forwards sendError() to /error, and Boot runs the
                        // security chain on that ERROR dispatch too. Leaving it authenticated
                        // meant the actuator chain's 401 challenge was rewritten to 403 by
                        // this chain's default entry point on the forward.
                        .requestMatchers("/error").permitAll()
                        // WebSocket handshake; STOMP CONNECT is authenticated separately (SEC-2)
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        // anonymous entry points: create session (creator becomes facilitator) and guest join
                        .requestMatchers(HttpMethod.POST, "/api/sessions").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/sessions/*/participants").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/decks").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * The operator account for the protected actuator endpoints. Defining this
     * also replaces Spring Boot's generated default user. When no password is
     * configured the manager holds no accounts at all, which is what closes
     * /actuator/metrics by default.
     */
    @Bean
    public UserDetailsService actuatorUserDetailsService(AppProperties props, PasswordEncoder encoder) {
        String password = props.actuator() == null ? null : props.actuator().password();
        if (password == null || password.isBlank()) {
            return new InMemoryUserDetailsManager();
        }
        String username = props.actuator().user() == null || props.actuator().user().isBlank()
                ? "actuator" : props.actuator().user();
        return new InMemoryUserDetailsManager(
                org.springframework.security.core.userdetails.User.withUsername(username)
                        .password(encoder.encode(password))
                        .roles("ACTUATOR")
                        .build());
    }
}
