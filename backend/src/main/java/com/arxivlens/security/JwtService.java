package com.arxivlens.security;

import com.arxivlens.config.AppProperties;
import com.arxivlens.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

@Service
public class JwtService {

    private final AppProperties props;
    private final SecretKey signingKey;

    public JwtService(AppProperties props) {
        this.props = props;
        byte[] secret = props.jwt().secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret must be at least 32 bytes (got " + secret.length + ").");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret);
    }

    public String issue(User user) {
        Instant now = Instant.now();
        Instant exp = now.plusMillis(props.jwt().expirationMs());
        return Jwts.builder()
                .issuer(props.jwt().issuer())
                .subject(user.getId().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claims(Map.of(
                        "email", user.getEmail(),
                        "role", user.getRole(),
                        "name", user.getDisplayName() == null ? "" : user.getDisplayName()
                ))
                .signWith(signingKey)
                .compact();
    }

    public Claims parse(String token) {
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(props.jwt().issuer())
                .build()
                .parseSignedClaims(token);
        return jws.getPayload();
    }

    public long expirationSeconds() {
        return props.jwt().expirationMs() / 1000;
    }
}
