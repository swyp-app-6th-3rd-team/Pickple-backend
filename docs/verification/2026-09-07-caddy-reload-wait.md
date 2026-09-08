# Caddy reload 기동 경쟁 — 로컬 재현 실측 (#135)

**측정일**: 2026-09-07 ~ 08
**대상**: `deploy-develop.yml` 의 SSM 커맨드 중 `up -d` → (대기) → `caddy reload` 구간
**증상 런**: [34132858477](https://github.com/swyp-app-6th-3rd-team/Pickple-backend/actions/runs/34132858477) —
서비스는 정상(`/actuator/health` 200, `/posts` 200)인데 워크플로만 빨간 **거짓 실패**

배포 워크플로는 로컬에서 그대로 못 돌린다. 그래서 **워크플로가 실제로 EC2 에 보내는 줄을
그대로 꺼내** 로컬 docker 에서 돌렸다. 손으로 옮겨 적은 스크립트가 아니라 워크플로 자체가
피검체다.

## 무엇을 어떻게 갈랐나

**피검체 추출.** `Deploy via SSM` 스텝의 `run:` 블록을 PyYAML 로 꺼내 bash 로 실행하되,
`aws` 를 스텁으로 바꿔 `--parameters commands=[...]` 인자를 가로챘다. 이 문자열을
① `json.loads` ② 실제 `aws` CLI(dead endpoint + `--debug`) 두 곳에 넣어 **같은 줄 배열로
파싱되는지** 확인했다. 셸 안의 셸이라 이스케이프가 깨지기 가장 쉬운 곳이다.

**픽스처.** `docker/docker-compose-ec2.yml` 의 caddy 서비스를 그대로 축소했다 — 같은 이미지
(`caddy:2-alpine`), 같은 bind mount(`./Caddyfile:/etc/caddy/Caddyfile:ro`), 같은
`restart: unless-stopped`. Caddyfile 은 `:8080 { respond "v1" }` 하나.

```yaml
services:
  caddy:
    image: caddy:2-alpine
    container_name: reloadtest-caddy
    restart: unless-stopped
    ports: ["18080:8080"]
    volumes: ["./Caddyfile:/etc/caddy/Caddyfile:ro"]
    mem_limit: 128m
```

경쟁을 재현하려고 기동 시점을 override 로 조작했다.

| override | entrypoint | 재현하는 상황 |
|---|---|---|
| slow-start | `sleep 6; exec caddy run ...` | 실패 런처럼 호스트 부하로 admin 포트가 늦게 열림 |
| never-ready | `sleep 3600` | 컨테이너는 Running 인데 :2019 가 영영 안 열림 |
| frozen | 정상 기동 뒤 `docker pause` | 연결(TCP handshake)은 되는데 응답이 없음 |

실행은 추출한 줄 중 `set -euxo pipefail` + `up -d` 부터 `caddy reload` 까지를 픽스처
디렉터리에서 `bash` 로 돌렸다(SSM 이 하는 것과 같다). frozen 만은 컨테이너가 얼어 exec 도
얼기 때문에, 같은 네트워크 네임스페이스를 공유하는 사이드카(`--network container:...`)에서
프로브 루프만 돌렸다.

## 결과 — 이전 → 이후

| 케이스 | 이전(대기 없음) | 이후(대기 있음) |
|---|---|---|
| slow-start 후 reload | **exit 1, 1초** — `dial tcp [::1]:2019: connect: connection refused` (실패 런과 같은 메시지) | **exit 0, 6초** — 기다린 뒤 `adapted config to JSON` |

## 결과 — 완료 판정 대조

| 이슈 완료 판정 | 케이스 | 결과 |
|---|---|---|
| 배포가 성공으로 끝난다 | slow-start (위) | exit 0, 6초 |
| Caddyfile 만 바꾼 배포에서도 새 설정이 반영된다 | 같은 compose 설정으로 안정 상태 → Caddyfile 만 `v2` 로 → `up -d` → 대기 → reload | compose 출력 `Container reloadtest-caddy Running`(재생성 없음), 응답 **v1 → v2**. reload 를 지우면 여기서 v1 이 남는다 |
| 잘못된 Caddyfile 은 여전히 배포를 실패시킨다 | 닫는 중괄호를 뺀 Caddyfile → `up -d` → 대기 → reload | **exit 1, 0초** — `Error: adapting config using caddyfile: syntax error: unexpected token 'broken'`. 컨테이너는 옛 설정(v2)을 계속 서빙 |
| 대기가 무한정 걸리지 않는다 | never-ready | **exit 1, 62초** — stderr 에 `caddy admin API :2019 not ready after 60s, reload skipped` |
| (추가) 연결은 되는데 응답이 없음 | frozen | **exit 143, 60초** (busybox `timeout` 이 루프를 죽임). 아래 "이종 리뷰가 바꾼 것" |
| (추가) 기동 크래시 루프 | 깨진 Caddyfile 로 컨테이너 재생성 → Restarting | **exit 1, 1초** — docker 가 `Container <id> is restarting, wait until the container is running` 을 내고 exec 자체가 실패. 기다려도 낫지 않는 상황이라 즉시 실패가 맞다 |
| (추가) 문법 | YAML 파싱 · JSON 파싱 · 실제 `aws` CLI 파싱 | 셋 다 같은 12줄 |

## 실측이 바꾼 것 ① — `localhost` 가 아니라 `127.0.0.1`

첫 구현은 `wget http://localhost:2019/config/` 였고 **slow-start 케이스가 60초 타임아웃으로
실패했다.** 컨테이너 안에서 갈라 보니:

| 확인 | 결과 |
|---|---|
| `/proc/net/tcp` 의 :2019 리스너 | `0100007F:07E3` — **127.0.0.1 하나** |
| `wget http://localhost:2019/` | `Connecting to localhost:2019 ([::1]:2019)` → refused |
| `wget http://127.0.0.1:2019/` | OK |

Caddy 의 admin 주소는 `localhost:2019` 로 적혀 있지만 리스너는 127.0.0.1 에만 열린다.
Alpine 의 `/etc/hosts` 는 `localhost` 를 127.0.0.1 과 ::1 둘 다로 풀고, busybox wget 은
첫 주소(::1)만 시도하고 폴백하지 않는다. `caddy reload` 자체가 `localhost` 로도 되는 이유는
Go 다이얼러가 풀린 주소를 전부 순회하기 때문이다. **성공 경로를 안 돌렸으면 영원히
실패하는 프로브를 "타임아웃" 으로 읽고 넘어갔을 것이다.**

## 이종 리뷰가 바꾼 것 ② — 횟수 상한이 아니라 벽시계 상한

첫 구현의 상한은 `for i in $(seq 1 30); do wget -T 2 ... && exit 0; sleep 2; done` 의
**30회**였고 메시지는 "60s" 라고 적었다. Codex 리뷰가 지적했다 — 연결은 되는데 응답이 없으면
시도마다 `-T 2` 가 붙어 실제 상한은 두 배다. frozen 픽스처로 실측했다.

| 루프 | frozen 에서 종료까지 |
|---|---|
| 30회 횟수 상한 (첫 구현) | **121초**, 메시지는 "after 60s" (거짓) |
| `timeout 60 sh -c 'until ...; do sleep 2; done'` (최종) | **60초**, exit 143 |

busybox `timeout` 은 `caddy:2-alpine` 의 2.7/2.8/현재 태그 모두에 있다.

**재현할 때의 함정.** `docker run caddy:2-alpine sh -c 'timeout 60 sh -c "..."'` 로 재현하면
**timeout 이 영영 안 죽는다.** ash 는 `-c` 의 마지막 단순 명령을 fork 없이 exec 하므로 timeout →
sh 가 차례로 PID 1 이 되고, PID 1 은 핸들러 없는 신호를 무시한다. `docker run --init` 이나
`docker compose exec`(caddy 가 PID 1)에서는 정상 동작한다 — 실제 배포 경로는 후자다.

## 그 밖에 잰 것

| 확인 | 결과 |
|---|---|
| 정상 기동에서 admin API 가 열리기까지 | 컨테이너 start 뒤 **50ms 이내** (5회) — 보통 첫 회에 빠져나간다 |
| `curl` 유무 | 2.7-alpine 없음 · 2.8-alpine 없음 · 현재(2.11) 있음. `wget`·`timeout`·`seq` 는 셋 다 있음 |
| busybox wget 의 non-2xx | `/does-not-exist` → rc=1 (재시도로 흡수). `/config/` → rc=0, JSON |
| running 이 아닌 컨테이너에 exec | Created · Exited 둘 다 **0초에 rc=1** — 프로브가 exec 에서 걸려 매달리지 않는다 |

## 로컬이 증명하지 못하는 것 — 머지 후 확인

- 실제 EC2 의 SSM 셸에서 같은 줄이 같은 순서로 도는지 — `develop` 배포 런이 green 으로 끝나는지
- 실제 부하(이미지 pull·압축 해제)에서 60초 상한이 충분한지 — 런 로그에서 대기 줄 다음에
  바로 reload 가 이어지는지. 타임아웃 메시지가 보이면 상한을 올릴 것이 아니라 왜 안 뜨는지를 본다
- Caddyfile 만 바꾼 배포의 반영 — 식별 가능한 변경(예: 응답 헤더)을 넣고 배포 뒤 응답으로 확인
- 프로브는 `GET /config/`, reload 는 `POST /load` 다. 같은 admin 서버가 둘을 서빙하므로 사이에
  틈은 없다고 보지만, docker 재현으로는 그 틈의 부재를 증명하지 못한다
