package app.pickple.point.domain;

import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;

import java.util.Optional;

/**
 * 피커 랭킹 화면의 읽기 모델 저장소 (§2.5 · §3.1 · §7.3).
 *
 * <p>쓰기용 {@link RankingStore} 와 나눈 이유는 <b>방향이 다르기 때문</b>이다.
 * 그쪽은 배치가 순위를 <b>만드는</b> 경로이고, 여기는 화면이 그 결과를 <b>읽는</b> 경로다.
 * 한 인터페이스에 두면 조회 API 가 재계산 메서드를 볼 수 있게 되는데,
 * 그건 조회 시점 계산으로 가는 문을 열어두는 것이다 — ADR-0028 이 닫은 문이다.
 *
 * <p><b>순위를 여기서 세지 않는다.</b> 배치가 사전 계산한 {@code users.ranking} 을
 * 읽기만 한다. 조회 시점에 세면 조각 하나에 회원 전체를 정렬해야 한다
 * (200k 실측 97.6ms/조각, ADR-0028).
 *
 * <p>Spring Data 의 {@link ScrollPosition}·{@link Window} 를 그대로 쓴다(ADR-0004).
 */
public interface RankingQueryStore {

    /**
     * 상위 피커 목록 (§2.5).
     *
     * <p>{@code size} 는 화면이 5명을 쓰지만(정책은 "5명만 노출") 상수로 박지 않는다 —
     * 자르는 개수는 화면의 결정이고, 저장소는 요청받은 만큼 준다.
     *
     * @param size 최대 인원. 순위가 산정된 회원이 그보다 적으면 그만큼만 나온다
     */
    java.util.List<RankingView> findTop(int size);

    /**
     * 전체 랭킹 한 조각 (§3.1). 무한 스크롤용이다.
     *
     * <p>정렬·페이징은 전부 쿼리가 한다. 애플리케이션에서 거르면 조각 크기가
     * 어긋나고 커서가 가리키는 위치와 실제 마지막 행이 달라진다.
     *
     * @param position 커서. 첫 조각이면 {@link ScrollPosition#keyset()}
     * @param size     한 조각의 크기 (§3.1 은 10개 단위)
     */
    Window<RankingView> findSlice(ScrollPosition position, int size);

    /**
     * 본인의 랭킹 (§7.3).
     *
     * <p>순위가 아직 없어도({@code ranking IS NULL}) <b>행 자체는 돌아온다</b> —
     * 포인트와 등급은 순위와 무관하게 존재하기 때문이다. 비는 것은 순위 하나뿐이다.
     *
     * @return 활성 회원이 아니면 빈 값
     */
    Optional<RankingView> findByUser(Long userId);

    /**
     * 랭킹 한 줄 (§2.5·§3.1·§7.3 의 조회 데이터).
     *
     * <p>세 화면이 같은 필드를 쓰므로 한 레코드를 공유한다.
     *
     * <p><b>등급명칭이 아직 없다.</b> 명세의 조회 데이터는 등급을 포함하지만,
     * 등급 판정의 정본({@code Grade} enum)은 이슈 #25 가 만들고 있다. 같은 정책표
     * §2 를 두 패키지에 옮겨 적으면 정본이 둘이 되고 복제본은 어긋난다 —
     * #25 가 머지된 뒤 그쪽 {@code Grade} 로 이 레코드에 필드를 더한다.
     * 판정 입력인 {@code voteCount} 를 지금부터 함께 읽어두는 이유가 그것이다.
     *
     * @param ranking   TOP 피커 순위. 배치 사전계산값이며 아직 산정되지 않았으면
     *                  {@code null} 이다 (ADR-0028). 0 으로 접지 않는다 —
     *                  지어낸 순위는 실제 꼴찌와 구분되지 않는다
     * @param point     누적 포인트. 원장 합계의 캐시다 (R-14)
     * @param voteCount 누적 투표 횟수. 등급 판정의 두 번째 입력이다 —
     *                  {@code users.vote_count} 이며 배치가 {@code vote} 에서 채운다
     */
    record RankingView(
            Long userId,
            String nickname,
            String profileImageUrl,
            Integer ranking,
            long point,
            long voteCount) {
    }
}
