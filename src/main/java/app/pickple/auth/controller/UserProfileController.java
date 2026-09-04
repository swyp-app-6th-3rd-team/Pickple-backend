package app.pickple.auth.controller;

import app.pickple.auth.domain.Nickname;
import app.pickple.auth.domain.User;
import app.pickple.auth.security.CurrentUser;
import app.pickple.auth.service.UserProfileService;
import app.pickple.common.ApiResponse;
import app.pickple.common.ResponseCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "User", description = "닉네임 · 프로필")
@RestController
@RequiredArgsConstructor
public class UserProfileController {

    /**
     * 요청 검증용 패턴. 도메인의 {@link Nickname} 과 같은 규칙을 표현한다.
     *
     * <p>도메인이 검증 애노테이션에 의존하지 못하므로(ArchitectureTest) 패턴이 두 곳에 있다.
     * 대신 두 관문의 역할이 다르다 — 이쪽은 잘못된 요청을 400 으로 되돌리는 바깥 관문이고,
     * 도메인 생성자는 어느 경로로 들어오든 통과할 수 없는 마지막 관문이다.
     * 규칙이 바뀌면 {@code NicknameFormatTest} 가 두 곳의 어긋남을 잡는다.
     */
    private static final String NICKNAME_PATTERN = "^[가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z0-9]{1,5}$";

    private static final String AVAILABLE_MESSAGE = "사용 가능한 닉네임";
    private static final String TAKEN_MESSAGE = "이미 사용 중인 닉네임";

    private final UserProfileService userProfileService;

    @Operation(summary = "닉네임 사용 가능 여부",
            description = "입력 중 실시간으로 부른다. 형식 위반은 400. "
                    + "여기서 사용 가능이 나와도 등록까지의 사이에 선점될 수 있어, 등록은 409로 실패할 수 있다.")
    @GetMapping("/users/nickname/availability")
    public ApiResponse<NicknameAvailabilityResponse> checkNicknameAvailability(
            @Parameter(description = "확인할 닉네임", required = true)
            @RequestParam("value") String value) {
        boolean available = userProfileService.isNicknameAvailable(value);
        return ApiResponse.success(new NicknameAvailabilityResponse(
                available, available ? AVAILABLE_MESSAGE : TAKEN_MESSAGE));
    }

    @Operation(summary = "내 프로필 조회",
            description = "Authorization: Bearer {accessToken} 이 필요하다. 미인증은 401이다.")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/users/me")
    public ApiResponse<UserProfileResponse> me(@Parameter(hidden = true) @CurrentUser Long userId) {
        return ApiResponse.success(UserProfileResponse.from(userProfileService.getProfile(userId)));
    }

    @Operation(summary = "프로필 등록",
            description = "회원가입 직후 닉네임과 프로필 이미지를 등록한다. "
                    + "이미지를 주지 않으면 랜덤 기본 프로필이 채워진다.")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/users/profile")
    public ResponseEntity<ApiResponse<UserProfileResponse>> registerProfile(
            @Parameter(hidden = true) @CurrentUser Long userId,
            @Valid @RequestBody ProfileRequest request) {
        User saved = userProfileService.saveProfile(userId, request.nickname(), request.profileImageUrl());
        return ResponseEntity.status(ResponseCode.CREATED.status())
                .body(ApiResponse.of(ResponseCode.CREATED, UserProfileResponse.from(saved)));
    }

    @Operation(summary = "프로필 수정",
            description = "닉네임과 프로필 이미지를 바꾼다. 이미지를 주지 않으면 쓰던 이미지를 유지한다.")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/users/profile")
    public ApiResponse<UserProfileResponse> editProfile(
            @Parameter(hidden = true) @CurrentUser Long userId,
            @Valid @RequestBody ProfileRequest request) {
        return ApiResponse.success(UserProfileResponse.from(
                userProfileService.saveProfile(userId, request.nickname(), request.profileImageUrl())));
    }

    public record ProfileRequest(
            @NotBlank
            @Schema(description = "5자 이내의 한글·영문·숫자. 유일성은 등록 시점에 판정되므로 409 로 실패할 수 있다")
            @Pattern(regexp = NICKNAME_PATTERN, message = "닉네임은 5자 이내의 한글·영문·숫자만 쓸 수 있습니다.")
            String nickname,

            @Schema(description = "주지 않으면 쓰던 이미지를 유지한다")
            @Size(max = 500) String profileImageUrl) {
    }

    public record NicknameAvailabilityResponse(
            @Schema(description = "지금 이 닉네임을 쓸 수 있는지. 등록까지의 사이에 선점될 수 있다")
            boolean available,
            @Schema(description = "화면에 그대로 보여줄 안내", example = "사용 가능한 닉네임") String message) {
    }

    public record UserProfileResponse(
            @Schema(description = "사용자 식별자") Long userId,
            @Schema(description = "서비스 닉네임. 프로필 등록 전이면 null") String nickname,
            @Schema(description = "프로필 이미지. 등록 때 주지 않았으면 서비스가 고른 기본 이미지다")
            String profileImageUrl) {

        static UserProfileResponse from(User user) {
            return new UserProfileResponse(
                    user.id(),
                    user.nickname() == null ? null : user.nickname().value(),
                    user.profileImageUrl());
        }
    }
}
