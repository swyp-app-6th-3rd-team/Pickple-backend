package app.pickple.auth.security;

import app.pickple.common.ResponseCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * 인가 관문의 계정 상태 조회가 실패했을 때 <b>503</b> 을 돌려준다.
 *
 * <p><b>왜 이 필터가 따로 필요한가.</b> 세 겹의 사정이 겹친다.
 * <ol>
 *   <li>{@code ExceptionTranslationFilter} 는 {@code AccessDeniedException} 과
 *       {@code AuthenticationException} <b>만</b> 잡는다. 다른 예외는 그냥 통과시킨다.</li>
 *   <li>{@code @RestControllerAdvice}({@code GlobalExceptionHandler})는 {@code DispatcherServlet}
 *       <b>안</b>에서 돈다. 시큐리티 필터는 그 앞이므로 여기서 난 예외를 볼 수 없다.</li>
 *   <li>따라서 아무것도 하지 않으면 DB 장애가 컨테이너 기본 오류 페이지(500, HTML)로 나간다 —
 *       JSON 계약도 깨지고 원인도 감춰진다.</li>
 * </ol>
 *
 * <p>중요한 것은 <b>이것을 401 로 바꾸지 않는 것</b>이다. DB 장애를 "토큰이 유효하지 않다" 로
 * 내보내면 전 클라이언트가 재로그인으로 몰리고, 장애가 인증 실패로 위장돼 모니터링에서
 * 감춰진다 (ADR-0035 결정 3). 그래서 401 도 500 도 아닌 <b>503</b> 이다 —
 * "네 자격증명이 틀렸다" 가 아니라 "지금 우리가 확인할 수 없다" 이고, 재시도가 의미 있다.
 *
 * <p><b>잡는 범위를 좁힌다.</b> 데이터 접근·트랜잭션 예외만 503 으로 번역하고 나머지는
 * 그대로 올려보낸다. 전부 잡으면 코드 버그(NPE 등)까지 "일시적 장애" 로 위장돼
 * 같은 종류의 은폐가 다시 생긴다.
 *
 * <p>이 필터는 {@code AuthorizationFilter} 보다 <b>바깥</b>에 놓여야 한다 —
 * 안쪽에 두면 관문이 던진 예외가 이 필터를 지나지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountStateUnavailableFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (DataAccessException | TransactionException e) {
            // 이미 응답이 나가기 시작했으면 상태 코드를 바꿀 수 없다. 그대로 올린다.
            if (response.isCommitted()) {
                throw e;
            }
            log.error("계정 상태를 확인하지 못했다. 인증 실패가 아니라 일시적 장애로 응답한다: {}",
                    e.getClass().getSimpleName(), e);
            RestAuthenticationEntryPoint.write(
                    response, objectMapper, ResponseCode.ACCOUNT_STATE_UNAVAILABLE);
        }
    }
}
