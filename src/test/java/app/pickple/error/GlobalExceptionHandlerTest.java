package app.pickple.error;

import app.pickple.common.ResponseCode;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.ItemContainer;
import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostProduct;
import app.pickple.post.domain.PostType;
import app.pickple.vote.infra.VotePersistenceException;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        mockMvc = MockMvcBuilders.standaloneSetup(new ExceptionController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("R-02 상품 구성 위반은 400과 WARN으로 응답한다")
    void productCountViolationIsRequestError() throws Exception {
        assertRequestError("/test/errors/r-02");
    }

    @Test
    @DisplayName("R-04 선택지 구성 위반은 400과 WARN으로 응답한다")
    void optionCountViolationIsRequestError() throws Exception {
        assertRequestError("/test/errors/r-04");
    }

    @Test
    @DisplayName("이미지 컨테이너 용도 불일치는 400과 WARN으로 응답한다")
    void itemContainerPurposeViolationIsRequestError() throws Exception {
        assertRequestError("/test/errors/item-container-purpose");
    }

    @Test
    @DisplayName("INVALID_REQUEST ApiException은 400과 WARN으로 응답한다")
    void invalidRequestApiExceptionIsRequestError() throws Exception {
        assertRequestError("/test/errors/api-request");
    }

    @Test
    @DisplayName("분류되지 않은 IllegalStateException은 500과 스택이 있는 ERROR로 응답한다")
    void rawIllegalStateIsServerError() throws Exception {
        assertServerError("/test/errors/illegal-state", IllegalStateException.class);
    }

    @Test
    @DisplayName("투표 영속화 오류는 500과 스택이 있는 ERROR로 응답한다")
    void votePersistenceFailureIsServerError() throws Exception {
        assertServerError("/test/errors/vote-persistence", VotePersistenceException.class);
    }

    @Test
    @DisplayName("SYSTEM_ERROR ApiException은 500과 스택이 있는 ERROR로 응답한다")
    void systemApiExceptionIsServerError() throws Exception {
        assertServerError("/test/errors/api-system", ApiException.class);
    }

    private void assertRequestError(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResponseCode.INVALID_REQUEST.name()));

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private void assertServerError(String path, Class<? extends Throwable> exceptionType) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ResponseCode.SYSTEM_ERROR.name()));

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(exceptionType.getName());
        });
    }

    @RestController
    static class ExceptionController {

        @GetMapping("/test/errors/r-02")
        void productCountViolation() {
            new Post(1L, PostType.AGREE, PostCategory.ETC, "상품 없음", null)
                    .verifyPublishable();
        }

        @GetMapping("/test/errors/r-04")
        void optionCountViolation() {
            new Post(1L, PostType.AGREE, PostCategory.ETC, "선택지 없음", null)
                    .addProduct(new PostProduct(1L, "상품", 1_000L, null, 1))
                    .verifyPublishable();
        }

        @GetMapping("/test/errors/item-container-purpose")
        void itemContainerPurposeViolation() {
            new ItemContainer(1L, AttachType.PRODUCT).verifyUsableAs(AttachType.COMMENT);
        }

        @GetMapping("/test/errors/illegal-state")
        void illegalState() {
            throw new IllegalStateException("분류되지 않은 내부 상태 오류");
        }

        @GetMapping("/test/errors/vote-persistence")
        void votePersistence() {
            throw new VotePersistenceException("투표 행을 찾을 수 없습니다");
        }

        @GetMapping("/test/errors/api-request")
        void apiRequest() {
            throw new ApiException(ResponseCode.INVALID_REQUEST, "요청 처리 실패");
        }

        @GetMapping("/test/errors/api-system")
        void apiSystem() {
            throw new ApiException(ResponseCode.SYSTEM_ERROR, "내부 처리 실패");
        }
    }
}
