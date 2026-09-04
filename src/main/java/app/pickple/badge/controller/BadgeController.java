package app.pickple.badge.controller;

import app.pickple.auth.security.CurrentUser;
import app.pickple.badge.domain.Badge;
import app.pickple.badge.domain.BadgeProgress;
import app.pickple.badge.service.BadgeService;
import app.pickple.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 내 뱃지 현황과 미해제 미션.
 *
 * <p><b>둘 다 인증이 필요하다.</b> 게스트에게는 미션을 보여주지 않고
 * "로그인하고 뱃지를 획득해보세요" 를 띄우는 것이 명세다(§2.3) — 그 문구는 화면의 몫이고
 * 서버는 401 로 답하면 된다. 별도 매처 없이 {@code SecurityConfig} 의
 * {@code anyRequest().authenticated()} 에 걸린다.
 *
 * <p><b>클래스 레벨 {@code @RequestMapping} 을 두지 않는다.</b> 경로를 알려면 클래스와
 * 메서드 애노테이션을 머릿속에서 합성해야 하는 것을 피한다(ADR-0029).
 *
 * <p>경로에서 {@code /api} prefix 는 걷어냈다(#91).
 * 배포 도메인이 이미 {@code api.} 서브도메인을 쓰므로 path 에 다시 얹지 않는다.
 */
@Tag(name = "Badge", description = "뱃지 현황 · 미션")
@RestController
@RequiredArgsConstructor
public class BadgeController {

    private final BadgeService badgeService;

    @Operation(summary = "내 뱃지 현황",
            description = "획득·미획득 뱃지 전체와 수집 개수를 돌려준다. "
                    + "3X3 목록이 미획득 뱃지의 이름은 보여주고 일러스트만 가리므로(§12.2) "
                    + "미획득도 함께 내려간다.")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/users/me/badges")
    public ApiResponse<BadgeCollectionResponse> myBadges(
            @Parameter(hidden = true) @CurrentUser Long userId) {
        return ApiResponse.success(BadgeCollectionResponse.from(badgeService.getCollection(userId)));
    }

    @Operation(summary = "미해제 미션 진행률",
            description = "아직 달성하지 못한 미션을 계열마다 하나씩 돌려준다(§2.3). "
                    + "누적 계열과 일일 계열에서 각각 가장 낮은 임계값을 고른다. "
                    + "진행률은 퍼센트가 아니라 현재값과 목표값 두 수다 — 화면이 \"(0/10)\" 으로 쓴다. "
                    + "다 채운 계열은 빠지고, 8종을 모두 얻으면 빈 배열이다.")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/users/me/badges/missions")
    public ApiResponse<List<MissionResponse>> myMissions(
            @Parameter(hidden = true) @CurrentUser Long userId) {
        return ApiResponse.success(
                badgeService.getOpenMissions(userId).stream().map(MissionResponse::from).toList());
    }

    /**
     * @param collectedCount 수집한 뱃지 개수. §12.1 의 조회 데이터가 이 값 하나라
     *                       별도 엔드포인트를 두지 않고 여기 실었다
     */
    public record BadgeCollectionResponse(
            @Schema(description = "수집한 뱃지 개수", example = "2") int collectedCount,
            @Schema(description = "전체 뱃지. 미획득도 함께 준다") List<BadgeResponse> badges) {

        static BadgeCollectionResponse from(BadgeService.BadgeCollection collection) {
            return new BadgeCollectionResponse(
                    collection.collectedCount(),
                    collection.badges().stream()
                            .map(owned -> BadgeResponse.of(owned.badge(), owned.acquired()))
                            .toList());
        }
    }

    /**
     * @param code     안정 식별자. 표시명이 바뀌어도 이 값은 그대로다 —
     *                 클라이언트가 일러스트를 고를 때 이름이 아니라 이것을 쓴다
     * @param name     표시명. 정책이 "추후 수정" 을 예고한 값이다
     * @param acquired 획득 여부. 미획득이면 화면이 일러스트를 가린다 (§12.2)
     */
    public record BadgeResponse(
            @Schema(description = "안정 식별자", example = "TOTAL_VOTE_10") String code,
            @Schema(description = "표시명", example = "투표 꿈나무") String name,
            @Schema(description = "획득 조건 문구", example = "누적 투표 10회 달성") String description,
            @Schema(description = "조건 유형", example = "TOTAL_VOTE") String conditionType,
            @Schema(description = "목표값", example = "10") long threshold,
            @Schema(description = "이 뱃지를 획득했는지") boolean acquired) {

        static BadgeResponse of(Badge badge, boolean acquired) {
            return new BadgeResponse(
                    badge.code(),
                    badge.name(),
                    badge.description(),
                    badge.conditionType().name(),
                    badge.threshold(),
                    acquired);
        }
    }

    /**
     * 미해제 미션 하나.
     *
     * @param current 현재값. 목표를 넘지 않는다
     * @param goal    목표값. 화면은 두 수를 "(current/goal)" 로 쓴다
     */
    public record MissionResponse(
            @Schema(description = "안정 식별자", example = "TOTAL_VOTE_10") String code,
            @Schema(description = "미션 문구", example = "누적 투표 10회 달성") String description,
            @Schema(description = "조건 유형", example = "TOTAL_VOTE") String conditionType,
            @Schema(description = "현재값", example = "3") long current,
            @Schema(description = "목표값", example = "10") long goal) {

        static MissionResponse from(BadgeProgress progress) {
            Badge badge = progress.badge();
            return new MissionResponse(
                    badge.code(),
                    badge.description(),
                    badge.conditionType().name(),
                    progress.current(),
                    progress.goal());
        }
    }
}
