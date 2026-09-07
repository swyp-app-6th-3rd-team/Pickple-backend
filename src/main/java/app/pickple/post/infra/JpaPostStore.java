package app.pickple.post.infra;

import app.pickple.post.domain.ItemContainerAlreadyAttachedException;
import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostSort;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import app.pickple.post.infra.PostListQuerydslRepository.PostListRow;
import app.pickple.post.infra.PostListQuerydslRepository.PostListSlice;
import app.pickple.post.infra.RandomPostQuerydslRepository.RandomCardEntry;
import app.pickple.post.infra.RandomPostQuerydslRepository.RandomPostSlice;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;

@Component
@RequiredArgsConstructor
public class JpaPostStore implements PostStore {

    private static final String PRODUCT_CONTAINER_UNIQUE_KEY = "uk_product_container";

    private final PostRepository repository;
    private final PostProductRepository productRepository;
    private final PostListQuerydslRepository listRepository;
    private final RandomPostQuerydslRepository randomRepository;
    private final Clock clock;

    /**
     * 저장 전에 불변식을 강제한다 (R-02·R-04).
     *
     * <p>개수 제약은 {@code CHECK} 로 표현할 수 없어 스키마가 막지 못한다.
     * 호출자가 {@code verifyPublishable()} 을 잊으면 "찬반인데 상품 3개" 가
     * 그대로 저장되므로, 마지막 관문을 여기에 둔다.
     */
    @Override
    @Transactional
    public Post save(Post post) {
        post.verifyPublishable();
        LocalDateTime now = LocalDateTime.now(clock);
        if (post.id() == null) {
            PostEntity entity = PostEntity.fromWithoutOptions(post, now);
            repository.saveAndFlush(entity);
            entity.addInitialOptions(post, now);
            repository.flush();
            return entity.toDomain();
        }
        PostEntity entity = repository.findById(post.id())
                .orElseThrow(() -> new PostPersistenceException("게시글을 찾을 수 없습니다: id=" + post.id()));
        entity.applyState(post, now);
        return entity.toDomain();
    }

    @Override
    @Transactional
    public Post saveIfContainerFree(Post post) {
        try {
            return save(post);
        } catch (DataIntegrityViolationException exception) {
            if (!hasConstraint(exception, PRODUCT_CONTAINER_UNIQUE_KEY)) {
                throw exception;
            }
            throw new ItemContainerAlreadyAttachedException(exception);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Post> findById(Long id) {
        return repository.findById(id).map(PostEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsActiveById(Long id) {
        return repository.existsByIdAndDeletedAtIsNull(id);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> findAttachedItemContainerIds(Collection<Long> itemContainerIds) {
        if (itemContainerIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(productRepository.findAttachedItemContainerIds(itemContainerIds));
    }

    /**
     * 게시글 목록 조회 결과를 {@link Window} 로 감싼다.
     *
     * <p>{@code Window} 를 직접 만드는 이유는 정렬 키가 읽기 전용 생성 컬럼이라(인기순)
     * Spring Data 의 파생 keyset 스크롤을 쓸 수 없기 때문이다({@link PostListQuerydslRepository} 참조).
     * 다만 <b>타입은 그대로 쓴다</b> — {@code ScrollResponse.of(...)} 와 ArchUnit 규칙이
     * 그 위에 서 있다(ADR-0004).
     *
     * <p><b>두 문장 조회는 REPEATABLE READ 를 명시한다.</b> 저장소가 키 문장과 행 문장을 나눠 내므로
     * (ADR-0045) 둘이 한 스냅샷을 봐야 한다. MySQL 기본값과 같아 실제로 바뀌는 것은 없지만, 전제를
     * 애노테이션에 적어 두면 격리 수준을 낮추는 변경이 이 파일을 지나가게 된다. 바깥 트랜잭션에
     * 참여하면 그쪽 격리 수준을 따른다 — Spring 은 참여 트랜잭션의 격리를 검증하지 않으므로
     * 이 선언은 새 트랜잭션을 여는 경우에만 강제다. 저장소의 {@code requireSnapshot()} 은 트랜잭션의
     * 존재만 확인한다.
     *
     * <p><b>행 변환 코드가 사라졌다.</b> 조회가 {@code Object} 배열 대신 {@link PostListRow} 를
     * 직접 돌려주므로 컬럼 인덱스 상수와 드라이버 타입 방어({@code toCreatedAt}·{@code toRanking})가
     * 필요 없다. 그 계약은 이제 {@code PostListQuerydslRepository} 의 프로젝션이 지킨다.
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Window<PostListView> findSlice(
            PostCategory category, PostSort sort, ScrollPosition position, int size) {

        PostListCursor cursor = PostListCursor.from(position, sort);
        PostListSlice slice = listRepository.findSlice(category, sort, cursor, size);

        List<PostListView> content = slice.rows().stream().map(PostListRow::view).toList();
        return Window.from(content, positionFunction(sort, slice.rows()), slice.hasNext());
    }

    /**
     * 랜덤 카드도 목록과 같은 두 문장 경로다 (ADR-0043). 격리 수준 선언의 뜻은 {@link #findSlice} 와 같다 —
     * 실제 경계는 {@code PostService} 이고 여기의 선언은 낮추는 변경이 이 파일을 지나가게 하는 표지다.
     *
     * <p>카드 접기는 저장소로 갔다. 조회가 {@code Object} 배열 대신 접힌 {@link RandomCardEntry} 를
     * 돌려주므로 컬럼 인덱스 상수 16개와 드라이버 타입 방어({@code toLong}·{@code toNullableLong}·{@code toInt})가
     * 필요 없다. {@code hasNext} 는 키 문장이 정한다 — 행 문장의 카드 수로 세면 두 문장 사이에 지워진 글이
     * "다음이 있다" 를 조용히 없앤다.
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Window<RandomPostView> findRandomSlice(
            PostType type, Long viewerId, ScrollPosition position, int size, long initialSeed) {

        RandomPostCursor cursor = RandomPostCursor.from(position, type, initialSeed);
        RandomPostSlice slice = randomRepository.findSlice(type, viewerId, cursor, size);

        List<RandomPostView> content = slice.cards().stream().map(RandomCardEntry::view).toList();
        return Window.from(content, randomPositions(cursor, type, slice.cards()), slice.hasNext());
    }

    /**
     * 각 행의 커서 위치. {@code ScrollResponse} 는 마지막 행의 것만 쓰지만,
     * {@code Window} 계약상 어느 색인이든 물어볼 수 있으므로 행마다 만든다.
     *
     * <p>인기순 커서 값은 응답에 없는 점수라 {@link PostListRow} 가 뷰 곁에 들고 온다.
     */
    private static IntFunction<ScrollPosition> positionFunction(PostSort sort, List<PostListRow> rows) {
        return index -> {
            PostListRow row = rows.get(index);
            Object sortValue = switch (sort) {
                case LATEST -> row.view().createdAt();
                case POPULAR -> row.popularityScore();
            };
            return PostListCursor.toPosition(sort, sortValue, row.view().id());
        };
    }

    private static IntFunction<ScrollPosition> randomPositions(
            RandomPostCursor cursor, PostType type, List<RandomCardEntry> entries) {
        return index -> {
            RandomCardEntry entry = entries.get(index);
            return RandomPostCursor.toPosition(
                    cursor.seed(), type, entry.randomKey(), entry.view().id());
        };
    }

    private static boolean hasConstraint(Throwable throwable, String constraintName) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && sameConstraint(violation.getConstraintName(), constraintName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameConstraint(String actualName, String expectedName) {
        if (actualName == null) {
            return false;
        }
        String unquoted = actualName
                .replace("`", "")
                .replace("\"", "")
                .replace("'", "");
        int qualifierSeparator = unquoted.lastIndexOf('.');
        String simpleName = qualifierSeparator < 0
                ? unquoted
                : unquoted.substring(qualifierSeparator + 1);
        return simpleName.equalsIgnoreCase(expectedName);
    }
}
