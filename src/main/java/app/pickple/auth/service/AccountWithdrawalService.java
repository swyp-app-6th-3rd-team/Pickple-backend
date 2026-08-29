package app.pickple.auth.service;

import app.pickple.auth.apple.AppleProviderTokenService;
import app.pickple.auth.apple.AppleTokenClient;
import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 소셜 provider 연결 해제와 로컬 회원 탈퇴를 순서대로 조율한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountWithdrawalService {

    private final UserStore userStore;
    private final AppleProviderTokenService appleProviderTokenService;
    private final AppleTokenClient appleTokenClient;
    private final AccountWithdrawalPersistenceService persistenceService;

    public void withdraw(Long userId) {
        User user = userStore.findById(userId)
                .orElseThrow(() -> new ApiException(ResponseCode.UNAUTHORIZED));

        if (user.provider() == SocialProvider.APPLE) {
            appleProviderTokenService.findDecryptedByUserId(userId)
                    .ifPresentOrElse(
                            appleTokenClient::revokeRefreshToken,
                            () -> log.warn("Apple 회원 탈퇴 시 저장된 provider token이 없습니다: userId={}", userId));
        }

        // 외부 호출을 DB 트랜잭션 밖에서 끝낸 뒤 로컬 상태만 짧은 트랜잭션으로 확정한다.
        persistenceService.complete(userId);
    }
}
