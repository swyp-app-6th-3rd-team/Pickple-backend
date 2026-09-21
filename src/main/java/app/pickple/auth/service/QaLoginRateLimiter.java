package app.pickple.auth.service;

import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** 단일 앱 인스턴스의 QA 로그인 보호. DB 조회·BCrypt 전에 호출한다. */
public final class QaLoginRateLimiter {
    private static final long WINDOW_MILLIS = 60_000;
    private static final int IP_LIMIT = 60;
    private static final int ACCOUNT_LIMIT = 10;
    private final Clock clock;
    private final int maxClients;
    private final LinkedHashMap<String, Window> clients = new LinkedHashMap<>();

    public QaLoginRateLimiter(Clock clock) {
        this(clock, 4096);
    }

    QaLoginRateLimiter(Clock clock, int maxClients) {
        this.clock = clock;
        this.maxClients = maxClients;
    }

    /** 허용 시 0, 거부 시 Retry-After 초. 검사와 증가를 원자적으로 처리한다. */
    public synchronized long retryAfterSeconds(String clientIp, String loginId) {
        long now = clock.millis();
        // 삽입 순서가 만료 순서다. 활성 카운터를 축출하면 새 IP로 제한을 우회할 수 있다.
        while (!clients.isEmpty() && clients.firstEntry().getValue().expiresAt <= now) {
            clients.pollFirstEntry();
        }
        Window window = clients.get(clientIp);
        if (window != null && window.expiresAt <= now) {
            clients.remove(clientIp);
            window = null;
        }
        if (window == null) {
            if (clients.size() >= maxClients) {
                return retryAfter(clients.firstEntry().getValue().expiresAt, now);
            }
            window = new Window(now + WINDOW_MILLIS);
            clients.put(clientIp, window);
        }
        if (window.attempts >= IP_LIMIT) return retryAfter(window.expiresAt, now);
        window.attempts++;
        int attempts = window.accounts.getOrDefault(loginId, 0);
        if (attempts >= ACCOUNT_LIMIT) return retryAfter(window.expiresAt, now);
        window.accounts.put(loginId, attempts + 1);
        return 0;
    }

    private static long retryAfter(long expiresAt, long now) {
        return Math.max(1, (expiresAt - now + 999) / 1000);
    }

    private static final class Window {
        private final long expiresAt;
        private int attempts;
        // IP당 60회 상한이 있으므로 이 Map도 60개 이하로 제한된다.
        private final Map<String, Integer> accounts = new HashMap<>();

        private Window(long expiresAt) {
            this.expiresAt = expiresAt;
        }
    }
}
