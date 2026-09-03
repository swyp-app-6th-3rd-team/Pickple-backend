package app.pickple.auth.service;

import app.pickple.auth.domain.Nickname;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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
    @Transactional
    public User saveProfile(Long userId, String nickname, String profileImageUrl) {
        User user = activeUser(userId);
        Nickname requested = new Nickname(nickname);

        // 조기 피드백. 본인이 이미 쓰던 닉네임을 그대로 다시 내는 것은 중복이 아니다.
        if (!requested.equals(user.nickname()) && userStore.existsActiveNickname(requested.value())) {
            throw new ApiException(ResponseCode.NICKNAME_ALREADY_IN_USE);
        }

        String imageUrl = hasImage(profileImageUrl)
                ? profileImageUrl
                : orDefault(user.profileImageUrl());
        user.registerProfile(requested, imageUrl);

        try {
            return userStore.save(user);
        } catch (DataIntegrityViolationException e) {
            // 사전 확인을 통과한 뒤 유니크 제약이 거부했다 = 확인과 저장 사이에 선점당했다.
            // 무결성 위반 전부를 중복으로 뭉뚱그리지 않기 위해 원인을 좁혀 확인한다.
            if (isActiveNicknameConflict(e)) {
                throw new ApiException(ResponseCode.NICKNAME_ALREADY_IN_USE);
            }
            throw e;
        }
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

    /**
     * 닉네임 유니크 제약 위반인지 가려낸다.
     *
     * <p>{@code DataIntegrityViolationException} 에는 FK 위반·길이 초과도 섞인다.
     * 전부 "이미 쓰는 닉네임"으로 보고하면 원인을 엉뚱한 곳에서 찾게 된다 (ADR-0019).
     */
    private boolean isActiveNicknameConflict(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("uk_users_active_nickname")) {
                return true;
            }
        }
        return false;
    }
}
