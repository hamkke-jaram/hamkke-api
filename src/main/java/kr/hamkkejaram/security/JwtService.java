package kr.hamkkejaram.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import kr.hamkkejaram.common.AppProperties;
import kr.hamkkejaram.user.Role;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

@Service
public class JwtService {
    private final SecretKey key;
    private final AppProperties.Jwt props;
    private final Clock clock;

    public JwtService(AppProperties appProperties, Clock clock) {
        this.props = appProperties.jwt();
        this.key = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    public String issueAccessToken(long userId, Role role) {
        Instant now = clock.instant();
        return Jwts.builder()
                .issuer(props.issuer())
                .subject(Long.toString(userId))
                .claim("role", role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(props.accessTtl())))
                .signWith(key)
                .compact();
    }

    public long accessTtlSeconds() {
        return props.accessTtl().toSeconds();
    }

    public Optional<AuthUser> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(props.issuer())
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            long id = Long.parseLong(claims.getSubject());
            Role role = Role.valueOf(claims.get("role", String.class));
            return Optional.of(new AuthUser(id, role));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
