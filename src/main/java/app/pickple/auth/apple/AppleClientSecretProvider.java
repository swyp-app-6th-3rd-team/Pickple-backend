package app.pickple.auth.apple;

import app.pickple.auth.config.AppleProperties;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/** Apple token endpoint 인증에 쓰는 ES256 client_secret JWT를 만든다. */
@Component
public class AppleClientSecretProvider {

    private final AppleProperties properties;
    private final Clock clock;
    private volatile ECPrivateKey privateKey;

    public AppleClientSecretProvider(AppleProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public String create() {
        try {
            Instant now = clock.instant();
            return Jwts.builder()
                    .header().keyId(properties.keyId()).and()
                    .issuer(properties.teamId())
                    .subject(properties.clientId())
                    .audience().add(properties.issuer()).and()
                    .issuedAt(Date.from(now))
                    .expiration(Date.from(now.plus(properties.clientSecretValidity())))
                    .signWith(privateKey(), Jwts.SIG.ES256)
                    .compact();
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            // 키 원문이나 파싱 상세를 예외 메시지에 싣지 않는다.
            throw new ApiException(ResponseCode.APPLE_LOGIN_UNAVAILABLE);
        }
    }

    private ECPrivateKey privateKey() {
        ECPrivateKey loaded = privateKey;
        if (loaded != null) {
            return loaded;
        }
        synchronized (this) {
            if (privateKey == null) {
                privateKey = parsePrivateKey();
            }
            return privateKey;
        }
    }

    private ECPrivateKey parsePrivateKey() {
        try {
            byte[] pemBytes = Base64.getDecoder().decode(properties.privateKeyBase64());
            String pem = new String(pemBytes, StandardCharsets.US_ASCII);
            String encodedKey = pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] keyBytes = Base64.getDecoder().decode(encodedKey);
            var key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            if (!(key instanceof ECPrivateKey ecPrivateKey)) {
                throw new GeneralSecurityException("EC private key가 아닙니다.");
            }
            return ecPrivateKey;
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new ApiException(ResponseCode.APPLE_LOGIN_UNAVAILABLE);
        }
    }

}
