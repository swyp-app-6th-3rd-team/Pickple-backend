package app.pickple.auth.security;

import app.pickple.auth.service.JwtService;
import app.pickple.error.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authorization 헤더의 Bearer 토큰을 읽어 SecurityContext 를 채운다.
 *
 * <p>이 필터는 <b>인증 실패를 직접 응답하지 않는다.</b>
 * 토큰이 없거나 잘못되면 컨텍스트를 비운 채 다음으로 넘기고,
 * 접근 거부 판단과 응답 작성은 {@code AuthenticationEntryPoint}/{@code AccessDeniedHandler}
 * 가 맡는다. 응답 형식이 한 곳에서만 만들어지므로 일관성이 유지된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null) {
            try {
                JwtService.Authenticated authenticated = jwtService.parseAccessToken(token);
                var principal = new AuthenticatedPrincipal(authenticated.userId(), authenticated.role());
                var authentication = new UsernamePasswordAuthenticationToken(
                        principal, null,
                        List.of(new SimpleGrantedAuthority(authenticated.role().authority())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (ApiException e) {
                // 인증 실패는 여기서 응답하지 않는다. EntryPoint 가 처리한다.
                SecurityContextHolder.clearContext();
                log.debug("토큰 인증 실패: {}", e.code());
            }
        }
        chain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)) {
            String token = header.substring(PREFIX.length()).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }
}
