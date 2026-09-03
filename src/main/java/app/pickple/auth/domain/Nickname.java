package app.pickple.auth.domain;

import java.util.regex.Pattern;

/**
 * 닉네임 — 값 객체.
 *
 * <p>형식 규칙(5자 이내, 한글·영문·숫자만)은 값 하나로 판정되므로 도메인이 가진다.
 * 여기에 두면 컨트롤러든 배치든 이 타입을 거치는 모든 경로가 자동으로 지킨다.
 *
 * <p><b>유일성은 여기서 판정하지 않는다.</b> 다른 행을 봐야 하고 동시성이 걸리므로
 * 스키마의 {@code uk_users_active_nickname} 이 최종 방어선이다 (R-23).
 */
public final class Nickname {

    /** 명세 §1.5 — 5자 이내. */
    public static final int MAX_LENGTH = 5;

    /**
     * 한글(음절·자모)·영문·숫자만. 공백·특수문자·이모지는 이 클래스 밖이라 걸러진다.
     *
     * <p>수량자를 코드포인트가 아니라 문자 단위로 세면 BMP 밖 이모지가 2로 세어져
     * "5자"의 의미가 흔들린다. 애초에 허용 문자 집합이 전부 BMP 안이라
     * 이 패턴을 통과한 값은 길이 해석이 갈리지 않는다.
     */
    private static final Pattern ALLOWED =
            Pattern.compile("^[가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z0-9]{1," + MAX_LENGTH + "}$");

    private final String value;

    public Nickname(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("닉네임은 필수입니다.");
        }
        if (!ALLOWED.matcher(value).matches()) {
            throw new IllegalArgumentException("닉네임은 " + MAX_LENGTH + "자 이내의 한글·영문·숫자만 쓸 수 있습니다.");
        }
        this.value = value;
    }

    /** 형식 위반을 예외 없이 판정한다. 조회 API 가 400 을 내기 전에 쓴다. */
    public static boolean isValid(String value) {
        return value != null && ALLOWED.matcher(value).matches();
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Nickname nickname)) {
            return false;
        }
        return value.equals(nickname.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
