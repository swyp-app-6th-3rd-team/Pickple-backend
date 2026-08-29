package app.pickple.auth.service;

import app.pickple.auth.domain.RefreshTokenStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RefreshTokenRevocationServiceTest {

    @Mock
    private RefreshTokenStore refreshTokenStore;

    @Test
    void revokesInIndependentTransaction() throws Exception {
        RefreshTokenRevocationService service = new RefreshTokenRevocationService(refreshTokenStore);

        service.revokeAllForUser(7L);

        verify(refreshTokenStore).deleteByUserId(7L);
        Method method = RefreshTokenRevocationService.class
                .getMethod("revokeAllForUser", Long.class);
        assertThat(method.getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
    }
}
