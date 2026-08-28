package app.pickple.auth.security;

import app.pickple.auth.domain.Role;

/**
 * 인증된 요청의 주체. JWT 클레임만으로 구성되므로 요청마다 DB 를 조회하지 않는다.
 * {@code @CurrentUser} 가 이 레코드의 {@code userId} 를 꺼내 쓴다.
 */
public record AuthenticatedPrincipal(Long userId, Role role) {
}
