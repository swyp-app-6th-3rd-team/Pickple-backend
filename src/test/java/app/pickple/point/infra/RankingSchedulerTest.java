package app.pickple.point.infra;

import app.pickple.point.domain.RankingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class RankingSchedulerTest {
    private final RankingStore store = mock(RankingStore.class);
    private final RankingScheduler scheduler = new RankingScheduler(store);

    @Test
    void synchronizesBothSourcesBeforeRanking() {
        scheduler.refresh();
        var order = inOrder(store);
        order.verify(store).syncPointsFromLedger();
        order.verify(store).syncVoteCountsFromVotes();
        order.verify(store).recalculateRankings();
        verifyNoMoreInteractions(store);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void failedStepStopsThisRunAndNextRunRetriesFromTheBeginning(int step) {
        RuntimeException failure = new IllegalStateException("batch failure");
        switch (step) {
            case 1 -> when(store.syncPointsFromLedger()).thenThrow(failure).thenReturn(0);
            case 2 -> when(store.syncVoteCountsFromVotes()).thenThrow(failure).thenReturn(0);
            case 3 -> when(store.recalculateRankings()).thenThrow(failure).thenReturn(0);
            default -> throw new AssertionError(step);
        }
        assertThatCode(scheduler::refresh).doesNotThrowAnyException();
        verify(store).syncPointsFromLedger();
        verify(store, times(step >= 2 ? 1 : 0)).syncVoteCountsFromVotes();
        verify(store, times(step == 3 ? 1 : 0)).recalculateRankings();
        clearInvocations(store);

        scheduler.refresh();
        var order = inOrder(store);
        order.verify(store).syncPointsFromLedger();
        order.verify(store).syncVoteCountsFromVotes();
        order.verify(store).recalculateRankings();
        verifyNoMoreInteractions(store);
    }
}
