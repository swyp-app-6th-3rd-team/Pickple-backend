package app.pickple.auth.infra;

import app.pickple.auth.domain.Role;
import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "users", uniqueConstraints =
        @UniqueConstraint(name = "uk_users_provider", columnNames = {"provider", "provider_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private SocialProvider provider;

    @Column(name = "provider_id", length = 255)
    private String providerId;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "name", length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private User.State state;

    @Column(name = "nickname", length = 5)
    private String nickname;

    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    /**
     * 배치가 매기는 순위. <b>읽기 전용이다</b> (ADR-0041).
     *
     * <p>{@code insertable = false, updatable = false} 라 Hibernate 가 INSERT·UPDATE 의
     * 컬럼 목록에서 아예 제외한다. 그래서 프로필 저장 같은 평범한 쓰기가 배치 계산값을
     * 덮어쓸 수 없다 — 매핑이 불변식("애플리케이션은 이 컬럼에 쓰지 않는다")을 깨는 것이
     * 아니라 <b>강제</b>한다 (ADR-0028).
     *
     * <p>아직 산정되지 않았으면 {@code null} 이다. 0 으로 접지 않는다 —
     * 지어낸 순위는 실제 꼴찌와 구분되지 않는다.
     */
    @Column(name = "ranking", insertable = false, updatable = false)
    private Integer ranking;

    /**
     * 도달한 최고 등급의 레벨 (R-16). <b>읽기 전용이다</b> (ADR-0041) — 이 컬럼에 쓰는 경로는
     * 등급 저장소의 원자적 {@code UPDATE … WHERE highest_grade < :level} 하나뿐이다({@code GradeRepository}).
     * {@code insertable = false, updatable = false} 라 프로필 저장 같은 평범한 쓰기가 옛 스냅샷으로
     * 등급을 덮어쓸 수 없다 — {@code ranking} 과 같은 장치다. 매핑한 이유는 게시글 상세가 작성자 등급을
     * 프로젝션으로 읽기 위해서다(ADR-0046). 가입 시 기본값 1 이라 비지 않는다.
     *
     * <p>스키마가 {@code TINYINT UNSIGNED} 라 {@code Byte} 다 — {@code ddl-auto: validate} 가 JDBC 타입으로
     * 대조하므로 {@code Integer} 로 두면 기동에서 "wrong column type" 으로 깨진다({@code PostOptionEntity.displayOrder}
     * 와 같은 사정). 도메인 {@code Grade} 로의 복원은 프로젝션이 한다 — 엔티티는 DB 타입을 따른다.
     */
    @Column(name = "highest_grade", nullable = false, insertable = false, updatable = false)
    private Byte highestGrade;

    /**
     * 누적 포인트. 원장({@code point_history})에서 유도한 캐시이며 배치가 채운다 (R-14).
     *
     * <p>스키마가 {@code INT UNSIGNED} 라 {@code Integer} 다. 도메인 {@code RankingView} 는
     * {@code long} 으로 받지만 그 변환은 프로젝션이 한다 — 엔티티는 DB 타입을 따른다.
     */
    @Column(name = "point", nullable = false, insertable = false, updatable = false)
    private Integer point;

    /** 누적 투표 횟수. 배치가 {@code vote} 에서 채운다. 등급 판정의 입력이다. */
    @Column(name = "vote_count", nullable = false, insertable = false, updatable = false)
    private Integer voteCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private UserEntity(User user, LocalDateTime now) {
        this.id = user.id();
        this.provider = user.provider();
        this.providerId = user.providerId();
        this.email = user.email();
        this.name = user.name();
        this.role = user.role();
        this.state = user.state();
        this.nickname = toNicknameValue(user);
        this.profileImageUrl = user.profileImageUrl();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static UserEntity from(User user, LocalDateTime now) {
        return new UserEntity(user, now);
    }

    public void applyState(User user, LocalDateTime now) {
        this.providerId = user.providerId();
        this.email = user.email();
        this.name = user.name();
        this.state = user.state();
        this.nickname = toNicknameValue(user);
        this.profileImageUrl = user.profileImageUrl();
        this.updatedAt = now;
    }

    public User toDomain() {
        return User.restore(id, provider, providerId, email, name, role, state,
                nickname, profileImageUrl);
    }

    private static String toNicknameValue(User user) {
        return user.nickname() == null ? null : user.nickname().value();
    }
}
