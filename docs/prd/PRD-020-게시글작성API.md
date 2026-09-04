# PRD-020 — 게시글 작성 API

**이슈**: #17 · **명세**: [기능명세서 v0.3](../requirement/기능명세서%20v0.3.md) §5.2 · **작성**: 2026-09-04

## 무엇을 왜

인증 사용자가 찬반 PICK, A/B PICK, 일반 게시글을 하나의 `POST /posts`로 작성한다.
세 유형은 필요한 상품·사진·선택지가 서로 다르므로, 요청 필드의 존재 여부만 확인하지 않고
유형별 불변식을 검증한 뒤 게시글 애그리거트 전체를 한 번에 저장한다.

기능명세서 v0.2에서 입력 폼은 §5.3이었지만 v0.3에서는 §5.2로 이동했다. 화면 구조만
바뀌었고 필수값과 길이·개수 제한은 동일하다. 같은 절에서 상품명·주제·제목을 선택값 및
숫자 전용으로 적은 표기는 유형별 입력 데이터의 필수 표시와 실제 플레이스홀더에 어긋나므로,
이 사이클은 **유형별 입력 데이터의 별표를 필수 조건으로 사용하고 일반 텍스트를 허용한다.**

## API 계약

| Method | Path | 인증 | 성공 |
|---|---|---|---|
| POST | `/posts` | 필요 | `201 CREATED`, `{ "postId": number }` |

이미 다른 게시글 상품에 연결된 이미지 컨테이너는 사전 검사와 DB 유일성 충돌 모두
`409 CONFLICT`, `ITEM_CONTAINER_ALREADY_IN_USE`로 응답한다.

요청은 `type`, `category`, `title`, `description`, `products[]`를 사용한다. 상품은
`itemContainerId`, `name`, `price`, `linkUrl`을 가진다.

- `AGREE`: 상품 1개. 제목은 상품명으로 정하고 `사자`·`말자` 선택지를 서버가 만든다.
- `A_B`: 30자 이내 주제와 상품 2개. A·B 선택지가 각 상품을 가리키게 만든다.
- `GENERAL`: 30자 이내 제목. 상품과 선택지를 만들지 않는다.
- 설명은 선택이며 최대 300자, 상품명은 필수이며 최대 30자다.
- 가격은 선택이며 `0~999,999,999`다.
- `linkUrl`은 선택 텍스트다. 서버가 URL에 접속하지 않고 업무 길이 제한도 두지 않는다.
- 작성 카테고리에 목록 필터용 `ALL`은 허용하지 않는다.

## 범위

**포함**

- 유형별 게시글·상품·선택지 조립과 불변식 검증(R-01~R-04)
- 작성자가 업로드한 `PRODUCT` 용도 이미지 컨테이너의 존재·소유권·사진 수 검증
- 하나의 이미지 컨테이너가 두 상품 또는 두 게시글에 연결되지 않도록 하는 유일성 보장
- 게시글·상품·선택지를 한 트랜잭션으로 저장하고 실패 시 부분 행을 남기지 않는 처리
- 상품 URL을 `LONGTEXT`로 저장하는 `V12__post_product_unbounded_link_url.sql`
- API, 서비스, 도메인, 저장소 통합 테스트

**제외**

- 이미지 업로드 자체 — `POST /images`가 먼저 만든 컨테이너 ID를 사용한다(#18)
- 게시글 목록·상세·수정·삭제 — 별도 API 사이클이다
- 투표 참여와 결과 조회 — 이 API는 선택지까지만 만들며 투표 행은 만들지 않는다
- 상품 URL 크롤링·메타데이터 추출 — 서버는 입력 문자열에 네트워크 요청을 보내지 않는다
- 전체 HTTP 요청 크기 상한 — 단일 필드가 아니라 애플리케이션 전역 운영 정책으로 분리한다([#95](https://github.com/swyp-app-6th-3rd-team/Pickple-backend/issues/95))

## 완료 판정

| 판정 | 검증 방법 | 검증 위치 |
|---|---|---|
| 미인증 요청이 게시글을 남기지 않고 401을 반환 | 토큰 없이 작성 후 상태와 행 수 확인 | `PostCreationFlowIT.rejectsUnauthenticatedRequest` |
| 찬반은 상품 1개와 선택지 2개(`사자`·`말자`)를 저장 | API 작성 후 `post_product`, `post_option` 조회 | `PostCreationFlowIT.createsAgreePostFromUploadedImage` |
| A/B는 상품 2개와 각 상품을 가리키는 선택지 2개를 저장 | 선택지의 상품 FK를 A·B 상품과 대조 | `PostCreationFlowIT.createsAbPostWithProductOptions` |
| 일반은 상품과 선택지를 저장하지 않음 | 작성 후 두 테이블의 행 수가 0인지 확인 | `PostCreationFlowIT.createsGeneralPostWithoutProductsOrOptions` |
| 유형별 상품 개수 위반 시 부분 행이 남지 않음 | 잘못된 세 유형 요청 후 관련 행 수 확인 | `PostCreationFlowIT.rejectsWrongProductCountsWithoutPartialRows` |
| 찬반 사진은 1~3장, A/B는 상품마다 정확히 1장 | 0·1·3·4장과 A/B 경계 요청 | `PostCreationFlowIT.enforcesAgreePhotoBoundaries`, `enforcesAbPhotoCount` |
| 상품명 30자·설명 300자·가격 999,999,999 경계만 허용 | 경계값과 초과값 요청 비교 | `PostCreationFlowIT.acceptsMaximumFieldLimits`, `rejectsFieldLimitViolations` |
| A/B 주제와 일반 제목은 필수이며 최대 30자 | 빈 값·31자 요청이 400인지 확인 | `PostCreationFlowIT.requiresConditionalTitles` |
| 없는·타인 소유·다른 용도 컨테이너가 저장되지 않고 이미 연결된 컨테이너는 409를 반환 | 각 컨테이너 상태로 작성 후 응답 코드와 행 수 확인 | `PostCreationFlowIT.rejectsMissingForeignAndWrongTypeContainers`, `rejectsReusedContainer` |
| 형식과 길이를 제한하지 않은 링크 문자열이 손실 없이 저장 | 일반 문자열과 긴 문자열을 DB 값과 대조 | `PostCreationFlowIT.storesProductLinkAsText`, `PostProductTest` |

빌드 성공만으로 완료를 판정하지 않는다. 위 테스트가 develop과 병합된 스키마에서
실제 MySQL Testcontainers를 사용해 통과해야 한다.

## 후속 작업

- 전체 요청 크기 상한은 Caddy와 Spring 설정을 함께 정해야 한다. 이 PR에서 임의의 전역값을
  정하지 않고 [#95](https://github.com/swyp-app-6th-3rd-team/Pickple-backend/issues/95)에서
  운영·로컬 경계와 413 응답 계약을 함께 정한다.
