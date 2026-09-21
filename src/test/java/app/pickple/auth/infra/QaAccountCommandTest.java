package app.pickple.auth.infra;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.Nickname;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import javax.sql.DataSource;
import java.time.Clock;

class QaAccountCommandTest {
    @Test
    void invalidCredentialsAreRejectedBeforeOpeningDatabaseConnection() {
        DataSource dataSource = mock(DataSource.class);
        assertThatIllegalArgumentException().isThrownBy(() -> QaAccountCommand.create(
                dataSource, "invalid/id", "invalid-hash", new Nickname("테스터"), Clock.systemUTC()));
        assertThatIllegalArgumentException().isThrownBy(() -> QaAccountCommand.create(
                dataSource, "qa-user", "invalid-hash", new Nickname("테스터"), Clock.systemUTC()));
        verifyNoInteractions(dataSource);
    }

    @Test
    void passwordPolicyUsesUtf8BcryptLimit() {
        assertThatCode(() -> QaAccountCommand.validatePassword("가".repeat(24))).doesNotThrowAnyException();
        assertThatCode(() -> QaAccountCommand.validatePassword("a".repeat(72))).doesNotThrowAnyException();
        assertThatIllegalArgumentException().isThrownBy(() -> QaAccountCommand.validatePassword("a".repeat(11)));
        assertThatIllegalArgumentException().isThrownBy(() -> QaAccountCommand.validatePassword("가".repeat(25)));
    }

    @Test
    void qaCannotBeUsedAsOAuthRegistration() {
        assertThatIllegalArgumentException().isThrownBy(() -> SocialProvider.from("qa"));
        assertThat(SocialProvider.from("kakao")).isEqualTo(SocialProvider.KAKAO);
    }
}
