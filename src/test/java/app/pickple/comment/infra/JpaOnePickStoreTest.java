package app.pickple.comment.infra;

import app.pickple.comment.domain.DuplicatePickException;
import app.pickple.comment.domain.OnePick;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.time.Clock;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JpaOnePickStoreTest {
    @Mock private OnePickRepository repository;
    private JpaOnePickStore store;
    private final OnePick pick = new OnePick(1L, 2L, 3L);

    @BeforeEach
    void setUp() { store = new JpaOnePickStore(repository, Clock.systemUTC()); }

    @Test
    void translatesOnlyTheNamedDuplicatePickConstraint() {
        var failure = violation("comment_pick.uk_pick_user_post", 1062);
        when(repository.saveAndFlush(any())).thenThrow(failure);
        assertThatThrownBy(() -> store.saveIfAbsent(pick))
                .isInstanceOf(DuplicatePickException.class).hasCause(failure);
    }

    @Test
    void preservesForeignKeyFailures() {
        var failure = violation("fk_comment_pick_comment", 1452);
        when(repository.saveAndFlush(any())).thenThrow(failure);
        assertThatThrownBy(() -> store.saveIfAbsent(pick)).isSameAs(failure);
    }

    @Test
    void preservesOtherUniqueConstraintFailures() {
        var failure = violation("another_unique_key", 1062);
        when(repository.saveAndFlush(any())).thenThrow(failure);
        assertThatThrownBy(() -> store.saveIfAbsent(pick)).isSameAs(failure);
    }

    @Test
    void preservesUnknownIntegrityFailures() {
        var failure = new DataIntegrityViolationException("unknown");
        when(repository.saveAndFlush(any())).thenThrow(failure);
        assertThatThrownBy(() -> store.saveIfAbsent(pick)).isSameAs(failure);
    }

    private DataIntegrityViolationException violation(String key, int code) {
        return new DataIntegrityViolationException("DB failure", new ConstraintViolationException(
                "constraint", new SQLException("constraint", "23000", code), key));
    }
}
