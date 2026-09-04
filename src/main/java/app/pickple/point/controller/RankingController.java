package app.pickple.point.controller;

import app.pickple.auth.security.CurrentUser;
import app.pickple.common.ApiResponse;
import app.pickple.common.ResponseCode;
import app.pickple.common.ScrollResponse;
import app.pickple.error.ApiException;
import app.pickple.point.domain.RankingQueryStore.RankingView;
import app.pickple.point.service.RankingQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
// Jackson 3 는 databind 만 tools.jackson 으로 옮겼다. 애노테이션은 그대로
// com.fasterxml.jackson.annotation 에 있다 (CursorCodec 의 tools.jackson 과 다르다).
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 피커 랭킹과 내 포인트 (§2.5 · §3.1 · §7.3).
 *
 * <p><b>클래스 레벨 {@code @RequestMapping} 을 두지 않는다.</b> 핸들러 메서드 한 줄만 보고
 * 최종 경로를 알 수 있게 한다(ADR-0029).
 *
 * <p>경로에서 {@code /api} prefix 는 걷어냈다(#91). 배포 도메인이 이미 {@code api.}
 * 서브도메인을 쓰므로 path 에 다시 얹지 않는다.
 */
@Tag(name = "Ranking", description = "피커 랭킹 · 내 포인트")
@RestController
@RequiredArgsConstructor
public class RankingController {

    private final RankingQueryService rankingQueryService;

    @Operation(summary = "인기 피커 (TOP 5)",
            description = "포인트가 높은 상위 피커를 노출한다. 게스트도 볼 수 있다. "
                    + "포인트 보유자가 없으면 빈 배열이다 — 화면은 이때 "
                    + "\"아직 TOP 피커가 존재하지 않아요\" 를 표시한다.")
    @SecurityRequirements   // 공개 엔드포인트 (SecurityConfig 의 permitAll)
    @GetMapping("/rankings/top")
    public ApiResponse<List<RankingItem>> findTop(
            @Parameter(description = "노출 인원. 기본 5")
            @RequestParam(value = "size", required = false) Integer size) {
        return ApiResponse.success(
                rankingQueryService.findTop(size).stream().map(RankingItem::from).toList());
    }

    /**
     * 전체 랭킹 (§3.1).
     *
     * <p><b>본인 랭킹은 여기 실리지 않는다.</b> 목록은 게스트도 부르므로 응답 모양이
     * 로그인 여부에 따라 갈리면 클라이언트가 두 형태를 다뤄야 한다. 본인 순위는
     * {@code GET /users/me/points} 로 따로 받아 화면이 합친다 — 명세의
     * "본인 순위에 도달 시 합쳐서 스크롤" 은 클라이언트 표현이다.
     */
    @Operation(summary = "전체 피커 랭킹",
            description = "포인트가 높은 순서대로 노출한다. 무한 스크롤(10개 단위)이며 "
                    + "게스트도 볼 수 있다. 순위가 아직 산정되지 않은 회원은 목록에 오르지 않는다.")
    @SecurityRequirements   // 공개 엔드포인트 (SecurityConfig 의 permitAll)
    @GetMapping("/rankings")
    public ApiResponse<ScrollResponse<RankingItem>> findAll(
            @Parameter(description = "이전 응답의 nextCursor. 없으면 첫 조각")
            @RequestParam(value = "cursor", required = false) String cursor,
            @Parameter(description = "조각 크기. 기본 10")
            @RequestParam(value = "size", required = false) Integer size) {
        return ApiResponse.success(ScrollResponse.of(
                rankingQueryService.findSlice(cursor, size), RankingItem::from));
    }

    @Operation(summary = "내 포인트와 순위",
            description = "인증이 필요하다. 순위가 아직 산정되지 않았으면 ranking 이 null 이다 "
                    + "— 배치가 최대 5분마다 매기므로 가입 직후가 그렇다.")
    @GetMapping("/users/me/points")
    public ApiResponse<MyRankingResponse> findMine(
            @Parameter(hidden = true) @CurrentUser Long userId) {
        RankingView view = rankingQueryService.findMine(userId)
                .orElseThrow(() -> new ApiException(ResponseCode.NOT_FOUND, "회원을 찾을 수 없습니다."));
        return ApiResponse.success(MyRankingResponse.from(view));
    }

    /**
     * 랭킹 한 줄 — 조회 데이터 (§2.5 · §3.1).
     *
     * <p><b>등급명칭은 아직 없다.</b> 판정의 정본인 {@code Grade} 는 이슈 #25 가
     * 만들고 있어, 여기서 같은 정책표 §2 를 옮겨 적으면 정본이 둘이 된다.
     * #25 머지 후 후속 PR 에서 필드를 더한다.
     *
     * @param ranking 순위. 목록에는 산정된 회원만 오르므로 여기서는 항상 값이 있다
     */
    public record RankingItem(
            @Schema(description = "회원 식별자") Long userId,
            @Schema(description = "닉네임") String nickname,
            @Schema(description = "프로필 사진") String profileImageUrl,
            @Schema(description = "랭킹 순위. 1위가 가장 앞") Integer ranking,
            @Schema(description = "누적 포인트") long point) {

        static RankingItem from(RankingView view) {
            return new RankingItem(
                    view.userId(),
                    view.nickname(),
                    view.profileImageUrl(),
                    view.ranking(),
                    view.point());
        }
    }

    /**
     * 내 포인트와 순위 (§7.3).
     *
     * <p>목록의 한 줄과 필드가 같지만 <b>{@code ranking} 의 의미가 다르다</b> —
     * 목록은 산정된 회원만 담지만 여기서는 {@code null} 일 수 있다.
     * 0 으로 접지 않는다: 지어낸 순위는 실제 꼴찌와 구분되지 않는다 (ADR-0028).
     *
     * @param ranking 순위. <b>{@code null} 이면 아직 산정되지 않았다</b>(가입 직후).
     *                이때 필드 자체가 응답에서 빠진다 — 아래 {@code @JsonInclude} 참조
     */
    public record MyRankingResponse(
            @Schema(description = "회원 식별자") Long userId,
            @Schema(description = "닉네임") String nickname,
            @Schema(description = "프로필 사진") String profileImageUrl,

            // 미산정을 "없음" 으로 표현한다. null 을 그대로 실어 보내면 클라이언트가
            // "순위가 없다" 와 "필드를 못 받았다" 를 같은 방식으로 다뤄야 한다.
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "랭킹 순위. 아직 산정되지 않았으면 이 필드가 없다")
            Integer ranking,

            @Schema(description = "누적 포인트") long point) {

        static MyRankingResponse from(RankingView view) {
            return new MyRankingResponse(
                    view.userId(),
                    view.nickname(),
                    view.profileImageUrl(),
                    view.ranking(),
                    view.point());
        }
    }
}
