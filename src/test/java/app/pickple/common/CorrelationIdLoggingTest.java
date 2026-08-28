package app.pickple.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * 로그에 correlationId 가 실제로 실리는지 검증한다.
 *
 * <p>logback 패턴에 {@code %X{correlationId}} 를 넣어도 필터가 MDC 를 채우지 않으면
 * 늘 하이픈만 찍힌다. 그 상태는 **로그가 정상적으로 보이기 때문에** 알아채기 어렵다.
 * 여기서는 appender 를 붙여 MDC 값이 로깅 이벤트에 담기는지 직접 본다.
 */
class CorrelationIdLoggingTest {

    private static final String LOGGER_NAME = CorrelationIdLoggingTest.class.getName();

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private CorrelationIdFilter filter;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        filter = new CorrelationIdFilter();
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        MDC.clear();
    }

    @Test
    @DisplayName("헤더로 받은 X-Request-Id 가 로그 이벤트의 MDC 에 담긴다")
    void putsHeaderValueIntoMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "TEST-CID-12345");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = mock(FilterChain.class);
        doAnswer(invocation -> {
            // 필터 체인 안(= 요청 처리 도중)에 로그를 남긴다.
            LoggerFactory.getLogger(LOGGER_NAME).warn("요청 처리 중 경고");
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(request, response, chain);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getMDCPropertyMap())
                .containsEntry(CorrelationIdFilter.MDC_KEY, "TEST-CID-12345");
        // 응답 헤더로도 돌려줘야 클라이언트가 문의할 때 이 값을 댈 수 있다.
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("TEST-CID-12345");
    }

    @Test
    @DisplayName("헤더가 없으면 UUID 를 새로 만들어 넣는다")
    void generatesIdWhenHeaderMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = mock(FilterChain.class);
        doAnswer(invocation -> {
            LoggerFactory.getLogger(LOGGER_NAME).warn("헤더 없는 요청");
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(request, response, chain);

        String correlationId = appender.list.get(0).getMDCPropertyMap()
                .get(CorrelationIdFilter.MDC_KEY);

        assertThat(correlationId).isNotBlank();
        assertThat(correlationId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("요청이 끝나면 MDC 를 비운다")
    void clearsMdcAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "LEAK-CHECK");

        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        // 스레드가 재사용되므로 남아 있으면 다음 요청 로그에 엉뚱한 ID 가 찍힌다.
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    /**
     * correlationId 와 trace_id 가 <strong>같은 로그 이벤트에</strong> 함께 담기는지 본다.
     *
     * <p>이 둘이 동시에 찍혀야 두 추적 세계가 이어진다 —
     * Grafana 에서 느린 트레이스를 찾은 뒤 그 {@code trace_id} 로 로그를 조회하고,
     * 거기서 {@code correlationId} 를 얻어 클라이언트 문의와 대조할 수 있다.
     * 하나라도 빠지면 연결 고리가 끊긴다.
     *
     * <p>운영에서는 OTel 에이전트의 logback-mdc 계측이 {@code trace_id}/{@code span_id} 를
     * 넣는다. 여기서는 에이전트를 띄울 수 없으므로 <strong>에이전트가 하는 일을 MDC 에 직접
     * 재현</strong>해, logback 패턴이 두 값을 함께 실어 나르는지만 검증한다.
     * (에이전트가 실제로 MDC 를 채운다는 것은 별도로 컨테이너 로그에서 확인했다 —
     * {@code docs/research/otel-measurements.md} 참조)
     */
    @Test
    @DisplayName("correlationId 와 trace_id 가 같은 로그 이벤트에 함께 담긴다")
    void carriesCorrelationIdAndTraceIdTogether() throws Exception {
        String traceId = "76f7ba1344317e699207d9c5087697e8";
        String spanId = "6055ebab63ee7194";

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "REQ-TRACE-LINK");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = mock(FilterChain.class);
        doAnswer(invocation -> {
            // OTel 에이전트가 하는 일을 흉내 낸다.
            MDC.put("trace_id", traceId);
            MDC.put("span_id", spanId);
            LoggerFactory.getLogger(LOGGER_NAME).warn("요청 처리 중 경고");
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(request, response, chain);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getMDCPropertyMap())
                .as("두 ID 가 같은 이벤트에 있어야 트레이스와 로그를 이을 수 있다")
                .containsEntry(CorrelationIdFilter.MDC_KEY, "REQ-TRACE-LINK")
                .containsEntry("trace_id", traceId)
                .containsEntry("span_id", spanId);
    }
}
