package app.pickple.auth.service;

import app.pickple.auth.domain.AppleProviderTokenStore;
import app.pickple.auth.domain.RefreshTokenStore;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 외부 provider revoke 성공 뒤 로컬 탈퇴 상태를 원자적으로 확정한다. */
@Service
@RequiredArgsConstructor
public class AccountWithdrawalPersistenceService {

    private final UserStore userStore;
    private final RefreshTokenStore refreshTokenStore;
    private final AppleProviderTokenStore appleProviderTokenStore;

    @Transactional
    public void complete(Long userId) {
        User user = userStore.findById(userId)
                .orElseThrow(() -> new ApiException(ResponseCode.UNAUTHORIZED));

        if (user.isActive()) {
            user.withdraw();
            userStore.save(user);
        }
        refreshTokenStore.deleteByUserId(userId);
        appleProviderTokenStore.deleteByUserId(userId);
    }
}
