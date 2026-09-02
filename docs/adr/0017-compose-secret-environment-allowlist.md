# ADR-0017 — Compose는 `.env`를 보간하고 명시한 비밀만 컨테이너에 전달한다

**상태**: Accepted

## 맥락

[ADR-0013](0013-oidc-and-secrets-manager.md)은 EC2가 Secrets Manager에서 비밀을 조회하고
`fetch-secrets.sh`가 만든 `.env`를 Docker Compose가 `env_file`로 주입한다고 기록했다.

실제 `docker-compose-ec2.yml`은 `env_file`을 사용하지 않는다. Compose가 호스트의 `.env`를 변수
보간에 사용하고, 각 서비스의 `environment`에 선언된 값만 컨테이너로 전달한다. 이 차이를 남겨두면
Secrets Manager에 새 키를 추가하는 것만으로 애플리케이션에 전달된다고 오해할 수 있고, 필요하지 않은
비밀까지 컨테이너에 노출시키는 변경으로 이어질 수 있다.

## 결정

- `terraform/templates/fetch-secrets.sh.tftpl`은 Secrets Manager JSON을 `/opt/pickple/.env`로 변환한다.
- Docker Compose는 `.env`를 compose 파일의 변수 보간에만 사용한다.
- 컨테이너에는 `docker-compose-ec2.yml`의 `environment`에 명시한 변수만 전달한다.
- 런타임 비밀 키를 추가할 때는 Terraform의 `local.secret_keys`, fetch 스크립트 출력, Compose의
  서비스별 `environment` 전달 목록을 함께 검토한다.

이 결정은 ADR-0013의 배포 흐름 중 `docker compose 가 env_file 로 주입`한다는 설명을 대체한다.

## 결과

- MySQL과 애플리케이션 컨테이너가 각자 필요한 비밀만 받는 allowlist 경계가 유지된다.
- Secrets Manager에 존재하지만 Compose에 명시하지 않은 값은 컨테이너에 전달되지 않는다.
- 새 설정을 추가할 때 여러 목록을 함께 갱신해야 하므로 누락 가능성이 있다. 배포 전
  `docker compose config`와 애플리케이션 기동 검증으로 확인한다.
- `.env` 평문 파일은 계속 EC2 디스크에 존재하므로 파일 권한과 삭제 정책은 ADR-0013의 기존 결정을 따른다.

## 검토한 대안

| 대안 | 기각 사유 |
|---|---|
| `env_file`로 모든 값을 컨테이너에 전달 | 서비스에 필요하지 않은 비밀까지 노출되어 최소 권한 경계가 약해진다 |
| Spring이 Secrets Manager를 직접 조회 | 호스트 평문 파일은 줄지만 애플리케이션이 AWS에 결합되고 로컬 실행 계약이 달라진다 |
| 배포 워크플로가 환경변수를 직접 주입 | EC2 재생성 시 자가 복구가 불가능해 ADR-0013의 복구 목표를 깨뜨린다 |
