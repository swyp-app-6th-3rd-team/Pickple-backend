# docker/

컨테이너 관련 파일을 한곳에 모았다. 프로젝트 루트에 흩어져 있던 것을 옮긴 것이고,
**동작은 그대로다.**

| 파일 | 쓰이는 곳 | 누가 읽나 |
|---|---|---|
| `Dockerfile` | 앱 이미지 빌드 | `deploy-develop.yml` 의 `docker build -f docker/Dockerfile .` |
| `docker-compose-local.yml` | 로컬 개발 (MySQL 만) | 사람이 직접 |
| `docker-compose-ec2.yml` | 배포 (앱 + MySQL + Caddy) | 워크플로가 EC2 로 실어 보낸다 |
| `Caddyfile` | 리버스 프록시·TLS 종단 | `docker-compose-ec2.yml` 이 마운트 |

## 로컬 개발

앱은 IDE 에서 띄우고 MySQL 만 컨테이너로 쓴다(ADR-0024).

```bash
docker compose --env-file .env -f docker/docker-compose-local.yml up -d
```

### `--env-file .env` 는 생략할 수 없다

Compose 는 변수 보간(`${MYSQL_PASSWORD}`)에 쓸 `.env` 를 **실행 위치가 아니라 compose 파일과
같은 디렉터리**에서 찾는다. compose 파일이 `docker/` 로 오면서 Compose 는 `docker/.env` 를
보게 됐지만 `.env` 는 루트에 있다.

`env_file:` 로는 해결되지 않는다. 그건 **컨테이너에 주입할 환경변수**를 지정하는 것이고,
변수 보간은 그보다 이른 **파싱 시점**에 일어나기 때문이다(실험으로 확인).

EC2 배포는 영향이 없다. `deploy-develop.yml` 이 파일을 `/opt/pickple/docker-compose-ec2.yml` 로
평탄화해 배치하므로 `.env` 와 같은 디렉터리에 놓인다.

## 빌드 컨텍스트는 루트다

`Dockerfile` 이 `docker/` 안에 있어도 **빌드 컨텍스트는 저장소 루트**여야 한다.
Gradle 소스(`src/`, `build.gradle.kts`)를 `COPY` 하기 때문이다.

```bash
docker build -f docker/Dockerfile -t pickple:local .
#            ^^^^^^^^^^^^^^^^^^^^                  ^
#            Dockerfile 위치                       컨텍스트는 루트
```

`-f` 없이 `docker build docker/` 로 하면 소스를 찾지 못해 빌드가 깨진다.

## EC2 위에서는 경로가 평평하다

워크플로는 이 디렉터리의 파일을 base64 로 실어 보내 **`/opt/pickple/` 에 이름만으로 떨어뜨린다.**

```
저장소                              EC2
docker/docker-compose-ec2.yml  →   /opt/pickple/docker-compose-ec2.yml
docker/Caddyfile               →   /opt/pickple/Caddyfile
```

그래서 EC2 에서 다루는 명령에는 `docker/` 가 붙지 않는다.

```bash
cd /opt/pickple
sudo docker compose -f docker-compose-ec2.yml ps
```

systemd 유닛(`pickple.service`)도 `/opt/pickple/docker-compose-ec2.yml` 을 가리킨다.
**이 경로를 바꾸려면 `terraform/templates/user-data.sh.tftpl` 의 유닛 정의도 함께 고쳐야 한다.**

## 주의

- **`docker-compose-ec2.yml` 의 포트는 terraform 과 짝이 맞아야 한다.**
  MySQL 호스트 포트(13307)는 `terraform/variables.tf` 의 `mysql_host_port` 와 같은 값이어야
  보안그룹이 열리는 포트와 실제 바인딩이 일치한다(ADR-0023).
- **로그는 `/data/logs` 에 bind 된다**(ADR-0025). bind mount 는 named volume 과 달리
  이미지의 소유권을 복사하지 않으므로, 호스트에 `chown 100:101 /data/logs` 가 되어 있어야
  앱(uid 100)이 로그를 쓸 수 있다. `user-data.sh.tftpl` 이 처리한다.
- **`.env` 는 저장소에 없다.** EC2 에서는 `fetch-secrets.sh` 가 Secrets Manager 에서 받아 만든다(ADR-0013).

## 관련 문서

- ADR-0012 — 단일 EC2 + docker-compose 구성
- ADR-0013 — 배포 자격증명(OIDC)과 런타임 비밀(Secrets Manager) 분리
- ADR-0022 — Caddy TLS 종단
- ADR-0024 — 로컬 실행 환경과 compose 파일 구성
- ADR-0025 — 로그 단일 파일·영속 EBS
- `terraform/README.md` — 인프라 운영
