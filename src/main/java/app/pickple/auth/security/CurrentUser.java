package app.pickple.auth.security;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 파라미터에 붙여 현재 로그인 사용자 ID 를 바로 받는다.
 *
 * <pre>
 * &#64;GetMapping("/me")
 * public ApiResponse&lt;MeResponse&gt; me(@CurrentUser Long userId) { ... }
 * </pre>
 *
 * <p>별도 {@code HandlerMethodArgumentResolver} 없이 {@code @AuthenticationPrincipal} 의
 * SpEL 로 해결한다. 비로그인 요청이면 {@code null} 이 들어온다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@AuthenticationPrincipal(expression = "#this == 'anonymousUser' ? null : userId")
public @interface CurrentUser {
}
