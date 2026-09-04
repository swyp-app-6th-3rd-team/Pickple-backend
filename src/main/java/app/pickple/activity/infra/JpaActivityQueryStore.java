package app.pickple.activity.infra;

import app.pickple.activity.domain.ActivityQueryStore;
import app.pickple.activity.domain.ActivitySort;
import app.pickple.activity.domain.ActivityType;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.IntFunction;

import static app.pickple.activity.infra.ActivityListRepository.Column;

/**
 * 활동 목록 결과를 {@link Window} 로 감싼다.
 *
 * <p>{@code Window} 를 직접 만드는 이유는 정렬 키가 매핑되지 않은 생성 컬럼이거나
 * (인기순) 조인 상대 테이블의 컬럼이라(최신·오래된순) Spring Data 의 파생 keyset
 * 스크롤을 쓸 수 없기 때문이다. 다만 <b>타입은 그대로 쓴다</b> —
 * {@code ScrollResponse.of(...)} 와 ArchUnit 규칙이 그 위에 서 있다(ADR-0004).
 */
@Component
@RequiredArgsConstructor
public class JpaActivityQueryStore implements ActivityQueryStore {

    private final ActivityListRepository repository;

    @Override
    @Transactional(readOnly = true)
    public ActivitySummary summarize(Long userId) {
        Object[] row = repository.summarize(userId);
        return new ActivitySummary(toLong(row[0]), toLong(row[1]), toLong(row[2]));
    }

    @Override
    @Transactional(readOnly = true)
    public Window<ActivityPostView> findSlice(
            Long userId, ActivityType type, ActivitySort sort, ScrollPosition position, int size) {

        ActivityListCursor cursor = ActivityListCursor.from(position, sort);
        List<Object[]> rows = repository.findSlice(userId, type, sort, cursor, size);

        // size + 1 건을 요청했으므로, 넘치면 다음 조각이 있다는 뜻이다.
        boolean hasNext = rows.size() > size;
        List<Object[]> page = hasNext ? rows.subList(0, size) : rows;

        List<ActivityPostView> content = page.stream().map(JpaActivityQueryStore::toView).toList();
        return Window.from(content, positionFunction(sort, page), hasNext);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ActivityPostView> findRecentVotePosts(Long userId, LocalDateTime since, int limit) {
        return repository.findRecentVotePosts(userId, since, limit).stream()
                .map(JpaActivityQueryStore::toView)
                .toList();
    }

    /**
     * 각 행의 커서 위치. {@code ScrollResponse} 는 마지막 행의 것만 쓰지만,
     * {@code Window} 계약상 어느 색인이든 물어볼 수 있으므로 행마다 만든다.
     */
    private static IntFunction<ScrollPosition> positionFunction(ActivitySort sort, List<Object[]> rows) {
        return index -> {
            Object[] row = rows.get(index);
            Object sortValue = sort.byActivityTime()
                    ? toLocalDateTime(row[Column.ACTIVITY_AT])
                    : toLong(row[Column.POPULARITY_SCORE]);
            return ActivityListCursor.toPosition(sort, sortValue, toLong(row[Column.ID]));
        };
    }

    private static ActivityPostView toView(Object[] row) {
        return new ActivityPostView(
                toLong(row[Column.ID]),
                PostType.valueOf((String) row[Column.TYPE]),
                PostCategory.valueOf((String) row[Column.CATEGORY]),
                (String) row[Column.TITLE],
                (String) row[Column.DESCRIPTION],
                toLong(row[Column.VOTE_COUNT]),
                toLong(row[Column.COMMENT_COUNT]),
                toLocalDateTime(row[Column.CREATED_AT]),
                (String) row[Column.THUMBNAIL_URL],
                toLocalDateTime(row[Column.ACTIVITY_AT]));
    }

    /** 네이티브 결과의 시각 컬럼은 드라이버에 따라 타입이 갈린다. */
    private static LocalDateTime toLocalDateTime(Object raw) {
        if (raw instanceof LocalDateTime value) {
            return value;
        }
        if (raw instanceof java.sql.Timestamp value) {
            return value.toLocalDateTime();
        }
        return LocalDateTime.parse(raw.toString().replace(' ', 'T'));
    }

    /** 네이티브 결과의 수치 컬럼은 드라이버가 정하는 타입으로 온다(BigInteger·Integer·Long). */
    private static long toLong(Object raw) {
        return raw == null ? 0L : ((Number) raw).longValue();
    }
}
