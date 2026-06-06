package com.arxivlens.security;

import com.arxivlens.config.AppProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AppProperties.class)
public class SecurityConfig {

    private final AppProperties props;

    public SecurityConfig(AppProperties props) {
        this.props = props;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Build the AuthenticationManager directly from a {@link DaoAuthenticationProvider}
     * constructed inline rather than as a separate {@code @Bean}.
     *
     * <p>If {@code DaoAuthenticationProvider} is registered as a top-level bean,
     * Spring Security's {@code InitializeUserDetailsBeanManagerConfigurer} logs a
     * misleading WARN at every startup:
     *   <pre>Global AuthenticationManager configured with an AuthenticationProvider bean.
     *   UserDetailsService beans will not be used by Spring Security for automatically
     *   configuring username/password login.</pre>
     * Functionally correct — we DO want our provider used — but the auto-config thinks
     * we might also be expecting it to wire a default formLogin off the UDS bean.
     * Inlining the provider stops the auto-config from seeing it as a candidate bean,
     * which silences the warning at the source.
     */
    @Bean
    public AuthenticationManager authenticationManager(AppUserDetailsService uds, PasswordEncoder enc) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(uds);
        provider.setPasswordEncoder(enc);
        return new org.springframework.security.authentication.ProviderManager(provider);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(props.cors().allowedOrigins());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        cfg.setExposedHeaders(List.of("Authorization"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtFilter) throws Exception {
        http
                .cors(c -> c.configurationSource(corsConfigurationSource()))
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 2FA management endpoints must come BEFORE the broad
                        // /api/auth/** permitAll below — first matching rule
                        // wins, and these need a logged-in user (you can't
                        // configure 2FA for an account you can't authenticate
                        // as).
                        .requestMatchers("/api/auth/2fa/**").authenticated()
                        .requestMatchers(
                                "/api/auth/**",
                                "/actuator/health",
                                "/actuator/info"
                        ).permitAll()
                        // Workspace Gateway notification aggregation: authenticates via a
                        // Google id_token in the X-Google-Id-Token header (verified inside
                        // the handler), not a JWT — so it's permitAll at the filter layer.
                        .requestMatchers(HttpMethod.GET, "/api/notifications").permitAll()
                        // External-cron triggers authenticate with a shared secret
                        // token inside the handler (no JWT), so they're permitAll at
                        // the filter layer. CronController rejects a missing/bad token.
                        .requestMatchers(HttpMethod.POST, "/api/cron/**").permitAll()
                        // <img src> tags can't send an Authorization header, so
                        // the proxy must be reachable anonymously. The service
                        // restricts upstreams to a publisher allow-list so this
                        // doesn't enable arbitrary SSRF.
                        .requestMatchers(HttpMethod.GET, "/api/images/proxy").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/papers/sync/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST,   "/api/sources/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT,    "/api/sources/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/sources/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST,   "/api/topics/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT,    "/api/topics/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/topics/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
