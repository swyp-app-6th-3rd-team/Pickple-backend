package app.pickple.post.infra;

import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostQueryStore;
import app.pickple.post.domain.PostSort;
import app.pickple.post.domain.PostType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import static app.pickple.post.infra.PostListRepository.Column;

/**
 * 게시글 목록·랜덤 카드 조회 결과를 {@link Window} 로 감싼다.
 *
 * <p>{@code Window} 를 직접 만드는 이유는 정렬 키가 매핑되지 않은 생성 컬럼이라
 * Spring Data 의 파생 keyset 스크롤을 쓸 수 없기 때문이다({@link PostListRepository} 참조).
 * 다만 <b>타입은 그대로 쓴다</b> — {@code ScrollResponse.of(...)} 와 ArchUnit 규칙이
 * 그 위에 서 있다(ADR-0004).
 */
@Component
@RequiredArgsConstructor
public class JpaPostQueryStore implements PostQueryStore {

    private final PostListRepository repository;
    private final RandomPostRepository randomRepository;

    @Override
    @Transactional(readOnly = true)
    public Window<PostListView> findSlice(
            PostCategory category, PostSort sort, ScrollPosition position, int size) {

        PostListCursor cursor = PostListCursor.from(position, sort);
        List<Object[]> rows = repository.findSlice(category, sort, cursor, size);

        // size + 1 건을 요청했으므로, 넘치면 다음 조각이 있다는 뜻이다.
        boolean hasNext = rows.size() > size;
        List<Object[]> page = hasNext ? rows.subList(0, size) : rows;

        List<PostListView> content = page.stream().map(JpaPostQueryStore::toView).toList();
        return Window.from(content, positionFunction(sort, page), hasNext);
    }

    @Override
    @Transactional(readOnly = true)
    public Window<RandomPostView> findRandomSlice(
            PostType type, Long viewerId, ScrollPosition position, int size, long initialSeed) {

        RandomPostCursor cursor = RandomPostCursor.from(position, type, initialSeed);
        List<Object[]> rows = randomRepository.findSlice(type, viewerId, cursor, size);
        List<RandomCardEntry> entries = groupByCard(rows);

        boolean hasNext = entries.size() > size;
        List<RandomCardEntry> page = hasNext ? entries.subList(0, size) : entries;
        List<RandomPostView> content = page.stream().map(RandomCardEntry::view).toList();
        return Window.from(content, randomPositions(cursor, type, page), hasNext);
    }

    /**
     * 각 행의 커서 위치. {@code ScrollResponse} 는 마지막 행의 것만 쓰지만,
     * {@code Window} 계약상 어느 색인이든 물어볼 수 있으므로 행마다 만든다.
     */
    private static IntFunction<ScrollPosition> positionFunction(PostSort sort, List<Object[]> rows) {
        return index -> {
            Object[] row = rows.get(index);
            Object sortValue = switch (sort) {
                case LATEST -> PostListRepository.toLocalDateTime(row[Column.CREATED_AT]);
                case POPULAR -> toLong(row[Column.POPULARITY_SCORE]);
            };
            return PostListCursor.toPosition(sort, sortValue, toLong(row[Column.ID]));
        };
    }

    private static PostListView toView(Object[] row) {
        return new PostListView(
                toLong(row[Column.ID]),
                PostType.valueOf((String) row[Column.TYPE]),
                PostCategory.valueOf((String) row[Column.CATEGORY]),
                (String) row[Column.TITLE],
                (String) row[Column.DESCRIPTION],
                toLong(row[Column.VOTE_COUNT]),
                toLong(row[Column.COMMENT_COUNT]),
                toCreatedAt(row[Column.CREATED_AT]),
                (String) row[Column.THUMBNAIL_URL],
                toLong(row[Column.AUTHOR_ID]),
                (String) row[Column.AUTHOR_NICKNAME],
                toRanking(row[Column.AUTHOR_RANKING]));
    }

    /**
     * 랭킹은 <b>없을 수 있다</b> — 가입 직후 다음 배치까지, 그리고 탈퇴 회원이 그렇다.
     *
     * <p>{@link #toLong} 을 쓰지 않는 이유가 여기 있다. 그쪽은 null 을 0 으로 접는데,
     * 순위 0 은 존재하지 않는 값이라 "아직 모른다" 를 "0위" 라는 거짓으로 바꾼다.
     * 미산정은 {@code null} 로 그대로 올려보내고 화면이 비운다.
     */
    private static Integer toRanking(Object raw) {
        return raw == null ? null : ((Number) raw).intValue();
    }

    private static LocalDateTime toCreatedAt(Object raw) {
        return PostListRepository.toLocalDateTime(raw);
    }

    /** 네이티브 결과의 수치 컬럼은 드라이버가 정하는 타입으로 온다(BigInteger·Integer·Long). */
    private static long toLong(Object raw) {
        return raw == null ? 0L : ((Number) raw).longValue();
    }

    private static IntFunction<ScrollPosition> randomPositions(
            RandomPostCursor cursor, PostType type, List<RandomCardEntry> entries) {
        return index -> {
            RandomCardEntry entry = entries.get(index);
            return RandomPostCursor.toPosition(
                    cursor.seed(), type, entry.randomKey(), entry.view().id());
        };
    }

    /** 두 선택지 행과 중복된 찬반 상품을 카드 하나로 접는다. SQL 순서를 그대로 보존한다. */
    private static List<RandomCardEntry> groupByCard(List<Object[]> rows) {
        Map<Long, RandomCardAccumulator> cards = new LinkedHashMap<>();
        for (Object[] row : rows) {
            long postId = toLong(row[RandomPostRepository.Column.ID]);
            RandomCardAccumulator card = cards.computeIfAbsent(postId, ignored -> new RandomCardAccumulator(row));
            card.addProduct(row);
            card.addOption(row);
        }
        return cards.values().stream().map(RandomCardAccumulator::toEntry).toList();
    }

    private static final class RandomCardAccumulator {

        private final long id;
        private final PostType type;
        private final String title;
        private final String description;
        private final long voterCount;
        private final Long selectedOptionId;
        private final long randomKey;
        private final Map<Long, RandomProductView> products = new LinkedHashMap<>();
        private final List<RandomOptionView> options = new ArrayList<>(2);

        private RandomCardAccumulator(Object[] row) {
            this.id = toLong(row[RandomPostRepository.Column.ID]);
            this.type = PostType.valueOf((String) row[RandomPostRepository.Column.TYPE]);
            this.title = (String) row[RandomPostRepository.Column.TITLE];
            this.description = (String) row[RandomPostRepository.Column.DESCRIPTION];
            this.voterCount = toLong(row[RandomPostRepository.Column.VOTER_COUNT]);
            this.selectedOptionId = toNullableLong(row[RandomPostRepository.Column.SELECTED_OPTION_ID]);
            this.randomKey = toLong(row[RandomPostRepository.Column.RANDOM_KEY]);
        }

        private void addProduct(Object[] row) {
            Long productId = toNullableLong(row[RandomPostRepository.Column.PRODUCT_ID]);
            if (productId == null) {
                return;
            }
            products.putIfAbsent(productId, new RandomProductView(
                    productId,
                    (String) row[RandomPostRepository.Column.PRODUCT_NAME],
                    toInt(row[RandomPostRepository.Column.PRODUCT_ORDER]),
                    (String) row[RandomPostRepository.Column.IMAGE_URL]));
        }

        private void addOption(Object[] row) {
            options.add(new RandomOptionView(
                    toLong(row[RandomPostRepository.Column.OPTION_ID]),
                    (String) row[RandomPostRepository.Column.OPTION_LABEL],
                    toNullableLong(row[RandomPostRepository.Column.OPTION_PRODUCT_ID]),
                    toInt(row[RandomPostRepository.Column.OPTION_ORDER]),
                    toLong(row[RandomPostRepository.Column.OPTION_VOTE_COUNT])));
        }

        private RandomCardEntry toEntry() {
            return new RandomCardEntry(randomKey, new RandomPostView(
                    id,
                    type,
                    title,
                    description,
                    voterCount,
                    selectedOptionId,
                    List.copyOf(products.values()),
                    List.copyOf(options)));
        }
    }

    private record RandomCardEntry(long randomKey, RandomPostView view) {
    }

    private static Long toNullableLong(Object raw) {
        return raw == null ? null : ((Number) raw).longValue();
    }

    private static int toInt(Object raw) {
        return ((Number) raw).intValue();
    }
}
