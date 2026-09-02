package app.pickple.auth.service;

import app.pickple.auth.domain.RefreshTokenStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 재사용·만료 탐지 시 바깥 예외 롤백과 무관하게 저장 토큰 폐기를 확정한다. */
@Service
@RequiredArgsConstructor
public class RefreshTokenRevocationService {

    private final RefreshTokenStore refreshTokenStore;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllForUser(Long userId) {
        refreshTokenStore.deleteByUserId(userId);
    }
}
