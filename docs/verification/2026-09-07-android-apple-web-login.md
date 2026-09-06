# Android Apple 웹 로그인 검증 기록 (#127)

## 기준

- 기준 커밋: `origin/develop` `ad2c067ac87646df8f258c31dcd43b9ffe2a53a2`
- 구현 브랜치: `feat/#127-apple-web-login`
- JDK: Amazon Corretto 25.0.3
- 로컬 날짜: 2026-09-07

## 자동 검증

### 통과

- JDK 25 `compileJava`, `compileTestJava`
- 전체 단위·아키텍처 테스트 433개
- Apple 웹·기존 Apple·탈퇴·HTTP 오류 경계 집중 테스트 68개
- ERD 세 소스와 SVG/HTML source hash 동기화 검사
- secret schema의 marker 중복·누락과 compose 공급 변수 검사

집중 테스트는 다음을 직접 확인한다.

- Services ID, HTTPS Return URL, `form_post`, 서버 state·nonce
- callback state 1회 claim, 취소와 실패 딥링크
- Services ID audience와 nonce, native/web 교차 거절
- 웹 code 교환의 `redirect_uri`, client secret subject, client별 revoke
- 딥링크의 자격 증명 미포함과 고정 `pickple://auth/callback`
- handoff verifier 불일치 거절, callback 뒤 탈퇴한 기존 신원 거절
- provider token의 user/client/state AAD와 기존 NATIVE API 회귀
- V4 format v1 provider 암호문을 V14의 NATIVE grant로만 복호화하는 호환성
- 만료된 미교환 WEB grant revoke, 성공 후 암호문 삭제, 실패 지표와 재시도 보존
- form 중복 key, 잘못된 method와 Content-Type의 명시적 4xx

### 로컬에서 실행 불가

`AppleWebLoginIT`와 `AppleWebLoginMigrationIT`는 MySQL Testcontainers가 필요한 테스트다. 현재 PC에서
Docker 환경을 찾지 못해 컨테이너와 ApplicationContext가 생성되기 전에 중단됐다. 제품 assertion
실패가 아니며, CI의 Ubuntu Docker 환경에서 다음을 검증하도록 커밋에 포함한다.

- Apple Origin·Origin 없음·다른 Origin·문자열 `null` 비교
- bearer/cookie 없는 form callback
- 같은 handoff code 동시 교환 두 요청 중 정확히 하나만 200
- native/web provider grant 공존과 탈퇴 시 client별 revoke
- fresh DB Flyway V14 적용과 Hibernate schema validate
- V13 기존 provider grant를 V14가 NATIVE로 보존하고 WEB 행과 공존시키는 upgrade migration

secret schema 검사는 로컬에서 통과했지만 Terraform 실행 파일과 적용 state를 읽지 못해
`.env.example`과 실제 `terraform output secret_keys` 비교는 건너뛰었다. CI의 secret-schema job이
저장소 정본 간 일치를 다시 확인한다.

## 아직 닫히지 않은 외부 검증

- Apple Developer Services ID, Primary App ID, 실제 develop Return URL 등록
- 실제 Apple authorization code 교환과 JWKS
- Galaxy에서 사용하는 Custom Tabs/WebView의 callback Origin과 deep link cold/warm start
- 앱 미설치와 브라우저 종료 UX
- develop 배포 후 비식별 request ID 기반 왕복

이 항목이 끝나기 전에는 “Galaxy Apple 로그인 E2E 완료”로 판정하지 않는다.
