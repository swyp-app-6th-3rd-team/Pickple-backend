# PRD-023 — Android Apple 웹 로그인

**상태**: 구현 완료, 실제 Apple·Galaxy E2E 대기  
**이슈**: #127

## 문제

Android 네이티브 앱은 iOS AuthenticationServices credential을 만들 수 없다. 기존
`POST /auth/apple`은 iOS가 받은 JSON credential과 앱 생성 nonce만 받으므로, Apple 웹 인증이
보내는 `application/x-www-form-urlencoded` 콜백을 처리할 수 없다. 기존 API를 Return URL로
지정하고 CORS만 허용하면 form/JSON, Bundle ID/Services ID, nonce 소유권, 앱 복귀와 토큰 전달
차이가 그대로 남는다.

## 목표

- 백엔드가 Apple 웹 로그인 시작과 HTTPS callback을 소유한다.
- 인증 결과를 고정 `pickple://auth/callback`으로 Galaxy 앱에 돌려보낸다.
- 딥링크에는 서비스 JWT나 Apple 자격 증명을 싣지 않는다.
- 기존 iOS `POST /auth/apple`의 Bundle ID·nonce 계약을 유지한다.
- Apple provider refresh token의 발급 client를 보존하고 탈퇴 시 같은 client로 revoke한다.

## 범위

### 포함

- `GET /auth/apple/web`: 앱의 S256 challenge와 app state를 받고 Apple authorize로 302
- `POST /auth/apple/web/callback`: Apple form callback의 state·nonce·token 검증과 303 앱 복귀
- `POST /auth/apple/web/exchange`: 60초 일회용 code와 앱 verifier를 교환해 Pickple JWT 발급
- MySQL 기반 로그인 시도 상태, TTL, 조건부 claim, 교환 시 비관적 잠금
- 만료된 미교환 provider grant의 WEB revoke, 실패 지표와 재시도
- native/web client별 provider token 저장·암호화 AAD·revoke
- 경로별 Security/CORS, OpenAPI, 설정·운영 문서, Flyway V14

### 제외

- Android 앱 코드와 Apple Developer Console 설정
- 운영 비밀값 반영과 배포
- 디바이스별 Pickple refresh session
- 이메일 기반 계정 병합
- Redis나 메시지 브로커 도입

## 요구사항

1. 시작 요청은 43자 S256 challenge, `S256`, 128비트 이상 URL-safe app state만 받는다.
2. 서버는 매 요청마다 256비트 state와 nonce를 만들고 원문 state를 저장하지 않는다.
3. callback은 state를 만료 전 한 번만 `PENDING → CALLBACK_PROCESSING`으로 바꾼다.
4. 웹 code 교환은 Services ID와 시작 때 사용한 고정 HTTPS Return URL을 사용한다.
5. ID token은 RS256 서명, issuer, Services ID audience, expiry, subject, 서버 nonce를 검증한다.
6. 성공 딥링크에는 백엔드 생성 일회용 code와 저장한 app state만 포함한다.
7. exchange는 S256 verifier, 만료, 미소비 상태를 한 트랜잭션과 잠금으로 확인한다.
8. 사용자/JWT, WEB provider token, handoff 소비는 같은 로컬 트랜잭션으로 확정한다.
9. 기존 active Apple 회원의 callback은 user ID에 바인딩한다. callback 뒤 탈퇴한 신원은 exchange가
   새 회원으로 만들지 않는다.
10. provider token 암호문은 user ID·client type·key ID를 AAD로 묶는다. V4의 format v1
    암호문은 NATIVE로 계속 읽는다.
11. callback CORS는 `https://appleid.apple.com`과 정확한 path/POST에만 적용하고 credential을
    허용하지 않는다. Origin이 없는 브라우저 탐색은 state·nonce로 검증한다.
12. 만료된 미교환 handoff는 client별 revoke 성공 뒤 암호문을 지우고, 실패 시 5분 이후 재시도한다.

## 완료 판정

| 판정 | 자동 검증 |
|---|---|
| 매 시작의 state·nonce가 다르고 Services ID·Return URL·form_post를 사용 | `AppleWebLoginServiceTest` |
| state 누락·만료·중복 callback에서 Apple 교환이 실행되지 않음 | 서비스 테스트 + `AppleWebLoginIT` |
| 딥링크에 일회용 code와 app state 외 자격 증명이 없음 | 컨트롤러/통합 Location 검사 |
| 잘못된 verifier·만료·재사용은 401이고 동시 교환은 하나만 성공 | 완료 서비스 테스트 + MySQL 동시성 IT |
| Apple/무 Origin callback은 허용하고 다른/null Origin은 거절 | 전체 SecurityFilterChain IT |
| native/web audience와 token 교환·revoke client가 분리 | verifier/gateway/client 테스트 |
| native/web provider grant가 공존하고 탈퇴 때 각각 revoke | 서비스 테스트 + MySQL IT |
| 교환 없이 만료된 WEB grant를 revoke하고 실패 시 보존·계수 | `AppleWebLoginCleanupServiceTest` |
| 기존 native token이 V14에서 NATIVE로 보존되고 JPA validate 통과 | Flyway 적용 + 전체 빌드 |
| 기능 비활성 시 웹만 503이고 native 계약은 회귀 없음 | 설정·기존 Apple 테스트 |

실제 Apple credential, Services ID 연결, Galaxy Custom Tabs/WebView, cold/warm start, 앱 미설치
동작은 Android 구현과 개발자 설정을 완료한 뒤 E2E 증거로 닫는다.

## 관련 문서

- [ADR-0040](../adr/0040-android-apple-web-login-handoff.md)
- [Apple 로그인 Runbook](../apple-sign-in-runbook.md)
- [검증 기록](../verification/2026-09-07-android-apple-web-login.md)
