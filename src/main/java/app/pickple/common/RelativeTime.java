package app.pickple.common;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 화면용 상대 시각 — {@code "0분 전"}·{@code "3시간 전"}·{@code "2일 전"}·{@code "1년 전"}.
 *
 * <p><b>정본이 하나여야 하는 이유</b>: 기능명세 §6.2(게시글 상세)와 §6.4(댓글)가
 * 조회 데이터에 *"작성 시간(0분전, 0시간 전, 0일 전, 0년 전으로 <b>통일</b>)"* 을
 * 같은 문구로 요구하는데, 둘은 <b>한 화면</b>이다 — 게시글 헤더의 "3시간 전" 과
 * 바로 아래 댓글의 "3시간 전" 이 나란히 놓인다. 계산을 복제하면 경계값
 * (59분→1시간, 364일→1년)에서 둘이 갈라져 그 "통일" 이 깨진다.
 *
 * <p>서버가 포맷하는 이유는 ADR-0046 에 있다. 요약하면 기기 시계가 틀린 사용자에게
 * {@code "-5분 전"} 이 나오는 것을 한 곳에서 막고, 플랫폼 셋이 경계 규칙을
 * 각자 재구현하지 않게 하기 위해서다. 응답은 {@code createdAt}(시각)을 함께 실어
 * 클라이언트가 자기 포맷을 쓸 여지를 남긴다.
 */
public final class RelativeTime {

    private RelativeTime() {
    }

    /**
     * {@code createdAt} 이 {@code now} 로부터 얼마나 지났는지를 화면 문구로 만든다.
     *
     * <p><b>미래 시각은 {@code "0분 전"} 이다.</b> 기기 시계가 앞서 있거나 저장 시각이
     * 미세하게 미래일 때 {@code "-5분 전"} 같은 문구가 나가는 것을 막는다.
     *
     * <p>1년은 365일로 센다. 윤년을 따지지 않는 것은 이 문구가 대략적인 경과를
     * 보여줄 뿐 날짜 계산에 쓰이지 않기 때문이다.
     */
    public static String of(LocalDateTime createdAt, LocalDateTime now) {
        Duration elapsed = Duration.between(createdAt, now);
        if (elapsed.isNegative()) {
            elapsed = Duration.ZERO;
        }

        long minutes = elapsed.toMinutes();
        if (minutes < 60) {
            return minutes + "분 전";
        }

        long hours = elapsed.toHours();
        if (hours < 24) {
            return hours + "시간 전";
        }

        long days = elapsed.toDays();
        if (days < 365) {
            return days + "일 전";
        }
        return days / 365 + "년 전";
    }
}
