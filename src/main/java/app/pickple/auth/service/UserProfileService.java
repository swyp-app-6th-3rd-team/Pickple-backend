package app.pickple.auth.service;

import app.pickple.auth.domain.Nickname;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 닉네임 사용 가능 여부 확인과 프로필 등록·수정·조회. */
@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserStore userStore;
    private final DefaultProfileImages defaultProfileImages;

    /**
     * 입력 중 닉네임이 쓸 수 있는지 알린다 (명세 §1.5).
     *
     * <p>여기서 "사용 가능"이 나와도 등록이 성공한다는 보장은 아니다.
     * 확인과 등록 사이에 다른 사람이 같은 닉네임을 가져갈 수 있다 —
     * 그 틈은 유니크 제약이 막는다 (R-23). 이 API 는 조기 피드백용이다.
     */
    @Transactional(readOnly = true)
    public boolean isNicknameAvailable(String nickname) {
        if (!Nickname.isValid(nickname)) {
            throw new ApiException(ResponseCode.INVALID_REQUEST);
        }
        return !userStore.existsActiveNickname(nickname);
    }

    @Transactional(readOnly = true)
    public User getProfile(Long userId) {
        return activeUser(userId);
    }

    /**
     * 프로필을 등록·수정한다.
     *
     * <p>등록과 수정을 한 메서드로 다룬다. 저장 규칙(닉네임 필수·유일, 이미지 없으면 기본)이
     * 양쪽에서 같아, 나누면 한쪽만 고쳐 어긋날 자리가 생긴다.
     */
    /**
     * <p><b>이 메서드에 {@code @Transactional} 을 붙이지 않는다.</b> 저장소가 닉네임
     * 제약 위반을 잡아 "저장되지 않았다" 는 사실로 바꾸는데, 바깥 트랜잭션이 열려 있으면
     * 그 위반이 트랜잭션을 rollback-only 로 만들어 커밋 단계에서 다시 터진다.
     * 이 흐름은 조회 한 번과 쓰기 한 번이라 묶어야 할 원자 구간도 없다.
     */
    public User saveProfile(Long userId, String nickname, String profileImageUrl) {
        User user = activeUser(userId);
        String imageUrl = hasImage(profileImageUrl)
                ? profileImageUrl
                : orDefault(user.profileImageUrl());
        user.registerProfile(new Nickname(nickname), imageUrl);

        // 저장소는 "저장됐다 / 안 됐다" 는 사실만 알린다.
        // 그것을 "이미 쓰는 닉네임" 이라는 정책으로 해석하는 것이 이 층의 일이다 (ADR-0019).
        return userStore.saveProfileIfNicknameFree(user)
                .orElseThrow(() -> new ApiException(ResponseCode.NICKNAME_ALREADY_IN_USE));
    }

    private User activeUser(Long userId) {
        if (userId == null) {
            throw new ApiException(ResponseCode.UNAUTHORIZED);
        }
        User user = userStore.findById(userId)
                .orElseThrow(() -> new ApiException(ResponseCode.UNAUTHORIZED));
        if (!user.isActive()) {
            throw new ApiException(ResponseCode.UNAUTHORIZED);
        }
        return user;
    }

    /** 이미 기본 프로필이 있으면 유지한다. 수정할 때마다 이미지가 바뀌면 사용자가 잃어버린 줄 안다. */
    private String orDefault(String current) {
        return hasImage(current) ? current : defaultProfileImages.pick();
    }

    private boolean hasImage(String url) {
        return url != null && !url.isBlank();
    }
}
