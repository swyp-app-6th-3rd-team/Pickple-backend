package app.pickple.auth.service;

import app.pickple.auth.config.AuthProperties;
import app.pickple.auth.domain.Role;
import app.pickple.auth.domain.User;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

/**
 * JWT 발급·검증.
 *
 * <p>액세스 토큰은 필요한 정보를 클레임에 담아 <b>요청마다 DB 를 조회하지 않는다.</b>
 * 요청마다 {@code loadUserByUsername} 을 부르면 인증이 DB 부하의 주범이 된다.
 */
@Slf4j
@Service
public class JwtService {

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey secretKey;
    private final AuthProperties.Jwt properties;
    private final Clock clock;

    public JwtService(AuthProperties properties, Clock clock) {
        this.properties = properties.jwt();
        this.secretKey = Keys.hmacShaKeyFor(this.properties.secretKey().getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    public String createAccessToken(User user) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(String.valueOf(user.id()))
                .issuer(properties.issuer())
                .claim(CLAIM_ROLE, user.role().name())
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.accessTokenValidity())))
                .signWith(secretKey)
                .compact();
    }

    public String createRefreshToken(User user) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(String.valueOf(user.id()))
                .issuer(properties.issuer())
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.refreshTokenValidity())))
                .signWith(secretKey)
                .compact();
    }

    public Authenticated parseAccessToken(String token) {
        Claims claims = parse(token);
        if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new ApiException(ResponseCode.INVALID_TOKEN, "액세스 토큰이 아닙니다.");
        }
        return new Authenticated(
                Long.valueOf(claims.getSubject()),
                Role.valueOf(claims.get(CLAIM_ROLE, String.class)));
    }

    public Long parseRefreshTokenSubject(String token) {
        Claims claims = parse(token);
        if (!TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new ApiException(ResponseCode.INVALID_TOKEN, "리프레시 토큰이 아닙니다.");
        }
        return Long.valueOf(claims.getSubject());
    }

    private Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .requireIssuer(properties.issuer())
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new ApiException(ResponseCode.EXPIRED_TOKEN);
        } catch (JwtException | IllegalArgumentException e) {
            // 서명 불일치·형식 오류 등. 원인을 응답에 노출하지 않는다.
            log.debug("토큰 파싱 실패: {}", e.getMessage());
            throw new ApiException(ResponseCode.INVALID_TOKEN);
        }
    }

    public LocalDateTime refreshTokenExpiresAt() {
        return LocalDateTime.ofInstant(clock.instant().plus(properties.refreshTokenValidity()), clock.getZone());
    }

    public Duration accessTokenValidity() {
        return properties.accessTokenValidity();
    }

    public Duration refreshTokenValidity() {
        return properties.refreshTokenValidity();
    }

    /**
     * 리프레시 토큰은 원문이 아니라 이 해시로 저장한다.
     */
    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다.", e);
        }
    }

    /** 액세스 토큰이 담고 있는 신원. DB 조회 없이 이것만으로 인가를 판단한다. */
    public record Authenticated(Long userId, Role role) {
    }
}
