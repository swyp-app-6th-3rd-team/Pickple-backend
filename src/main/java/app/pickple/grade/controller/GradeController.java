package app.pickple.grade.controller;

import app.pickple.auth.security.CurrentUser;
import app.pickple.common.ApiResponse;
import app.pickple.grade.domain.Grade;
import app.pickple.grade.domain.GradeProgress;
import app.pickple.grade.service.GradeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 등급 조회 (기능명세 §11.1 내 등급 · §11.2 전체 등급).
 *
 * <p><b>클래스 레벨 {@code @RequestMapping} 을 두지 않는다.</b> 핸들러 메서드 한 줄만 보고
 * 최종 경로를 알 수 있게 한다(ADR-0029).
 *
 * <p>경로에서 {@code /api} prefix 는 걷어냈다(#91). 배포 도메인이 이미 {@code api.}
 * 서브도메인을 쓰므로 path 에 다시 얹지 않는다.
 *
 * <p>두 경로 모두 인증이 필요하다. {@code anyRequest().authenticated()} 기본값에 걸리므로
 * {@code SecurityConfig} 를 건드리지 않는다 — 등급 화면은 마이페이지 하위라
 * 게스트 진입 경로가 없다(§11.2 트리거 "마이페이지에서 '나의 등급' 메뉴 탭").
 */
@RestController
@RequiredArgsConstructor
public class GradeController {

    private final GradeService gradeService;

    @Operation(summary = "내 등급 조회",
            description = "현재 등급과 누적 포인트·투표 횟수, 다음 등급까지의 달성률을 돌려준다. "
                    + "포인트는 원장 합계이므로 지급 직후 값이 곧바로 반영된다(R-14). "
                    + "등급은 내려가지 않는다(R-16).")
    @GetMapping("/users/me/grade")
    public ApiResponse<MyGradeResponse> readMyGrade(
            @Parameter(hidden = true) @CurrentUser Long userId) {
        return ApiResponse.success(MyGradeResponse.from(gradeService.readMyGrade(userId)));
    }

    @Operation(summary = "전체 등급 기준 조회",
            description = "LV.1~LV.5 의 승급 필요 조건을 낮은 등급부터 돌려준다.")
    @GetMapping("/grades")
    public ApiResponse<List<GradeResponse>> readAllGrades() {
        return ApiResponse.success(
                gradeService.readAllGrades().stream().map(GradeResponse::from).toList());
    }

    /**
     * 내 등급 현황.
     *
     * <p>등급 일러스트는 싣지 않는다. 이미지의 정본은 화면설계서인데 아직 확정 전이라
     * 서버가 URL 을 지어내면 그것이 계약이 된다. 프론트가 {@code level} 로 매핑한다.
     *
     * @param nextGrade       다음 등급. 최고 등급이면 {@code null} 이다
     * @param achievementRate 다음 등급까지의 달성률(0~100). 포인트와 투표 중
     *                        <b>덜 채운 쪽</b>이다 — 승급이 AND 조건이라 그쪽이 병목이다.
     *                        최고 등급이면 100
     */
    public record MyGradeResponse(
            @Schema(description = "현재 등급 레벨 1~5", example = "2") int level,
            @Schema(description = "등급 명칭", example = "LV.2") String name,
            @Schema(description = "누적 포인트. point_history 합계다", example = "250") long point,
            @Schema(description = "누적 투표 횟수. 재투표는 세지 않는다", example = "24") long voteCount,
            @Schema(description = "다음 등급. 최고 등급이면 null") GradeResponse nextGrade,
            @Schema(description = "다음 등급까지 달성률 0~100", example = "3") int achievementRate) {

        static MyGradeResponse from(GradeProgress progress) {
            return new MyGradeResponse(
                    progress.grade().level(),
                    progress.grade().displayName(),
                    progress.point(),
                    progress.voteCount(),
                    progress.nextGrade().map(GradeResponse::from).orElse(null),
                    progress.achievementRate());
        }
    }

    /**
     * 등급 하나의 승급 필요 조건 (§11.2 조회 데이터).
     *
     * <p>LV.1 은 두 조건이 모두 0 이다 — "가입 시 기본 부여" 를 별도 필드가 아니라
     * 조건 0 으로 표현한다. 그래야 화면이 다섯 등급을 같은 모양으로 그린다.
     */
    public record GradeResponse(
            @Schema(description = "등급 레벨 1~5", example = "2") int level,
            @Schema(description = "등급 명칭", example = "LV.2") String name,
            @Schema(description = "승급에 필요한 누적 포인트", example = "200") long requiredPoint,
            @Schema(description = "승급에 필요한 누적 투표 횟수", example = "20") long requiredVoteCount) {

        static GradeResponse from(Grade grade) {
            return new GradeResponse(
                    grade.level(),
                    grade.displayName(),
                    grade.requiredPoint(),
                    grade.requiredVoteCount());
        }
    }
}
