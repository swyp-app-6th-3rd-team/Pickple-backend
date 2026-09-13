package app.pickple.activity.infra;

import app.pickple.activity.domain.ActivityQueryStore;
import app.pickple.activity.domain.ActivitySort;
import app.pickple.activity.domain.ActivityType;
import app.pickple.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class JpaActivityQueryStoreIT {

    @Autowired
    private ActivityQueryStore activityQueryStore;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private Clock clock;

    @Test
    @DisplayName("READ COMMITTED로 연 외부 트랜잭션에서는 최근 투표 집계 조회를 거부한다")
    void recentPostsRejectLowerIsolation() {
        LocalDateTime since = LocalDateTime.now(clock).minusDays(7);

        assertThatThrownBy(() -> readCommitted().executeWithoutResult(status ->
                activityQueryStore.findRecentVotePosts(1L, since, 10)))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("REPEATABLE READ");
    }

    @Test
    @DisplayName("공유하는 활동 목록 조회도 READ COMMITTED 외부 트랜잭션을 거부한다")
    void activitySliceRejectsLowerIsolation() {
        assertThatThrownBy(() -> readCommitted().executeWithoutResult(status ->
                activityQueryStore.findSlice(1L, ActivityType.VOTE, ActivitySort.LATEST,
                        ScrollPosition.keyset(), 10)))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("REPEATABLE READ");
    }

    private TransactionTemplate readCommitted() {
        TransactionTemplate readCommitted = new TransactionTemplate(transactionTemplate.getTransactionManager());
        readCommitted.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        readCommitted.setReadOnly(true);
        return readCommitted;
    }
}
