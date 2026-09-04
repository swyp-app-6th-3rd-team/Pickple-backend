package app.pickple.vote.controller;

import app.pickple.auth.security.CurrentUser;
import app.pickple.common.ApiResponse;
import app.pickple.vote.service.VoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 투표 참여.
 *
 * <p>카드에서 버튼을 누르면 페이지 이동 없이 결과 게이지로 바뀌므로,
 * <b>투표 응답이 곧 결과 화면의 데이터</b>다. 투표하고 다시 조회하게 만들면
 * 그 사이 들어온 다른 표까지 섞여 내 표의 결과가 아닌 값을 보게 된다.
 *
 * <p>게스트의 최대 3회 선택은 클라이언트 로컬에서만 처리하고 서버에는 기록하지 않는다
 * (R-11). 따라서 이 경로는 {@code SecurityConfig} 의
 * {@code anyRequest().authenticated()} 에 걸려 인증 없이는 401 이다.
 */
@Tag(name = "Vote", description = "투표 참여 · 결과")
@RestController
@RequiredArgsConstructor
public class VoteController {

    private final VoteService voteService;

    @Operation(summary = "투표 참여",
            description = "선택지에 투표한다. 이미 투표했으면 선택만 바뀌고 투표 인원은 늘지 않는다(R-22). "
                    + "응답은 갱신된 선택지별 득표 수와 득표율이다.")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/posts/{postId}/votes")
    public ApiResponse<VoteResponse> vote(
            @PathVariable Long postId,
            @Parameter(hidden = true) @CurrentUser Long userId,
            @Valid @RequestBody VoteRequest request) {
        return ApiResponse.success(
                VoteResponse.from(voteService.castOrChange(postId, request.optionId(), userId)));
    }

    public record VoteRequest(
            @Schema(description = "고른 선택지 id. 이 게시글의 선택지여야 한다(R-10)", example = "1")
            @NotNull Long optionId) {
    }

    /**
     * @param voterCount 투표한 사람 수. 한 사람이 선택을 바꿔도 늘지 않는다 (R-09·R-22)
     */
    public record VoteResponse(
            @Schema(description = "게시글 식별자") Long postId,
            @Schema(description = "이번 요청으로 확정된 내 선택") Long selectedOptionId,
            @Schema(description = "투표한 사람 수. 한 사람이 선택을 바꿔도 늘지 않는다 (R-09·R-22)")
            long voterCount,
            @Schema(description = "선택지별 득표 수와 득표율. 그대로 결과 게이지가 된다")
            List<OptionResponse> options) {

        static VoteResponse from(VoteService.VoteResult result) {
            return new VoteResponse(
                    result.postId(),
                    result.selectedOptionId(),
                    result.voterCount(),
                    result.options().stream().map(OptionResponse::from).toList());
        }
    }

    /**
     * @param label      찬반만 값이 있다. A/B 선택지는 상품이 이름을 대신하므로 {@code null}
     * @param percentage 정수 퍼센트. 반올림 때문에 두 값의 합이 100 이 아닐 수 있다
     */
    public record OptionResponse(
            @Schema(description = "선택지 id") Long optionId,
            @Schema(description = "찬반 선택지의 라벨. A/B 는 null") String label,
            @Schema(description = "표시 순서. 1 또는 2") int displayOrder,
            @Schema(description = "이 선택지의 득표 수") long voteCount,
            @Schema(description = "정수 퍼센트. 반올림 때문에 두 값의 합이 100 이 아닐 수 있다. "
                    + "아무도 투표하지 않았으면 0")
            int percentage) {

        static OptionResponse from(VoteService.OptionTally tally) {
            return new OptionResponse(
                    tally.optionId(),
                    tally.label(),
                    tally.displayOrder(),
                    tally.voteCount(),
                    tally.percentage());
        }
    }
}
