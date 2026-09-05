package app.pickple.item.infra;

import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.ItemContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class JpaItemContainerStoreTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private ItemContainerRepository repository;

    private JpaItemContainerStore store;

    @BeforeEach
    void setUp() {
        store = new JpaItemContainerStore(repository, FIXED_CLOCK);
    }

    @Test
    @DisplayName("존재하지 않는 이미지 컨테이너 갱신은 영속화 오류로 분류한다")
    void missingContainerOnUpdateIsPersistenceFailure() {
        ItemContainer container = ItemContainer.restore(17L, 10L, AttachType.PRODUCT, List.of());
        given(repository.findById(17L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> store.save(container))
                .isInstanceOf(ItemContainerPersistenceException.class)
                .hasMessageContaining("id=17");
    }
}
