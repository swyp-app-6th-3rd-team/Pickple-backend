package app.pickple.auth.infra;

import app.pickple.auth.domain.SocialProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * package-private 이라 infra 패키지를 벗어날 수 없다.
 * 바깥에는 도메인의 {@code UserStore} 인터페이스만 노출된다.
 *
 * <p>중첩 인터페이스로 두면 Spring Data 가 리포지토리 빈을 만들지 않으므로
 * 반드시 최상위 타입이어야 한다.
 */
interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByProviderAndProviderId(SocialProvider provider, String providerId);

    /**
     * 활성 회원의 닉네임 점유 여부를 DB 가 센다.
     *
     * <p>생성 컬럼 {@code active_nickname} 을 조회 대상으로 삼는 이유는 두 가지다.
     * 유니크 인덱스가 걸려 있어 인덱스만 보고 끝나고, "활성 회원만" 이라는 조건이
     * 컬럼 정의에 이미 들어 있어 조회 쪽에서 state 조건을 빠뜨릴 수 없다.
     *
     * <p>동등성 판정은 컬럼 콜레이션(utf8mb4_0900_ai_ci)이 한다 —
     * 대소문자를 구분하지 않으므로 유니크 제약이 거부하는 값과 이 조회 결과가 일치한다.
     */
    @Query(value = "SELECT COUNT(*) FROM users WHERE active_nickname = :nickname", nativeQuery = true)
    long countActiveNickname(@Param("nickname") String nickname);

    /**
     * 인가 관문이 요청마다 부르는 존재 확인.
     *
     * <p>{@code SELECT 1} 로 컬럼을 읽지 않는다. 조건이 PK 하나라 실행계획이
     * {@code type=const} / {@code key=PRIMARY} / {@code rows=1} 로 끝난다(실측 0.03ms warm).
     * 엔티티를 로딩하면 컬럼 전체를 읽고 영속성 컨텍스트에 올리므로 그만큼 비싸진다.
     */
    @Query(value = "SELECT 1 FROM users WHERE id = :id AND state = 'ACTIVE'", nativeQuery = true)
    Optional<Integer> findActiveMarkerById(@Param("id") Long id);

    /** 닉네임의 현재 주인. 본인이 쓰던 닉네임을 다시 내는 경우를 중복과 가르는 데 쓴다. */
    @Query(value = "SELECT id FROM users WHERE active_nickname = :nickname", nativeQuery = true)
    Optional<Long> findIdByActiveNickname(@Param("nickname") String nickname);

    /**
     * 프로필을 쓴다. 닉네임 유일성은 {@code uk_users_active_nickname} 이 판정한다.
     *
     * <p><b>왜 조건 없는 UPDATE 인가.</b> {@code NOT EXISTS} 로 점유를 먼저 확인하면
     * 그 서브쿼리가 유니크 인덱스에 갭 잠금을 잡는다. 같은 닉네임으로 동시에 몰리면
     * 여러 트랜잭션이 서로 다른 순서로 잠금을 얻어 <b>데드락</b>이 난다(에러 1213).
     * 실측에서 8개 동시 요청 중 7개가 그렇게 죽었다.
     *
     * <p>조건을 빼면 각 트랜잭션은 자기 행만 잠그고 유니크 인덱스 항목 하나만 다툰다 —
     * 승자 하나가 잡고 나머지는 곧바로 제약 위반으로 떨어진다. 판정을 인덱스에 맡기는 편이
     * 갭 잠금을 걸고 스스로 판정하려는 것보다 경합이 적다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE users
               SET nickname = :nickname,
                   profile_image_url = :profileImageUrl,
                   updated_at = :updatedAt
             WHERE id = :id
               AND state = 'ACTIVE'
            """, nativeQuery = true)
    int updateProfile(@Param("id") Long id,
                      @Param("nickname") String nickname,
                      @Param("profileImageUrl") String profileImageUrl,
                      @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 로그인이 갱신하는 값만 쓴다. <b>활성 회원일 때만</b> 쓴다.
     *
     * <p><b>왜 변경 감지가 아니라 조건부 UPDATE 인가.</b> 로그인은 회원을 읽고
     * 활성인지 본 뒤 저장한다. 그 사이에 탈퇴가 커밋되면 읽어둔 옛 값이 그대로 쓰여
     * <b>파기한 개인정보와 {@code ACTIVE} 상태가 되살아난다</b>(개인정보처리방침 제3조,
     * [ADR-0040]). 확인과 쓰기 사이의 틈이라 응용 계층 검사로는 막을 수 없다.
     *
     * <p>엔티티에 가드를 두는 것으로도 부족하다 — 영속성 컨텍스트가 <b>읽은 시점</b>의
     * 엔티티를 1차 캐시로 돌려주므로, 엔티티의 {@code state} 를 봐도 여전히 옛 값이다.
     * 판정은 쓰기 시점의 <b>행</b>이 해야 하고, 그것을 아는 것은 DB 뿐이다.
     * {@code updateProfile} 이 같은 이유로 같은 조건을 건다.
     *
     * <p>반환값 0 은 "활성 회원이 아니어서 쓰지 않았다" 는 사실이다. 그 사실을 정책으로
     * 해석하는 것은 위층의 몫이다 (ADR-0019).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE users
               SET email = :email,
                   name = :name,
                   updated_at = :updatedAt
             WHERE id = :id
               AND state = 'ACTIVE'
            """, nativeQuery = true)
    int syncActiveProfile(@Param("id") Long id,
                          @Param("email") String email,
                          @Param("name") String name,
                          @Param("updatedAt") LocalDateTime updatedAt);
}
