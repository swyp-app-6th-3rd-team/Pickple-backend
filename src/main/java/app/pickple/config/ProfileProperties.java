package app.pickple.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 프로필 정책.
 *
 * <p>기본 프로필 이미지 목록을 코드가 아니라 설정으로 받는다. 디자인이 이미지를
 * 추가·교체할 때 배포 없이 바꿀 수 있어야 하고, 환경마다 CDN 주소가 다르기 때문이다.
 */
@ConfigurationProperties(prefix = "app.profile")
public record ProfileProperties(List<String> defaultImageUrls) {
}
