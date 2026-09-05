#!/usr/bin/env bash
# 마이그레이션 SQL 을 Flyway 와 같은 순서로 나열한다(줄바꿈 구분).
#
#   scripts/list-migrations.sh            src/main/resources/db/migration 를 읽는다
#   scripts/list-migrations.sh <디렉터리>  다른 디렉터리를 읽는다(테스트용)
#
# 왜 `sort` 로 안 되는가:
#   Flyway 는 버전을 '.' 또는 '_' 로 끊어 **조각마다 정수로** 비교한다
#   (MigrationVersion.compareTo). 그래서 V1 < V1.2 < V1.10 < V2 < V10 이다.
#   - `sort -t V -k2 -n` 은 1.10 을 소수로 읽어 V1.10 을 V1.2 앞에 놓는다.
#   - `sort -V` 는 V1.2 를 V1 **앞**에 놓아 역시 틀린다.
#   둘 다 실측으로 확인했다. 그래서 비교를 직접 구현한다.
#
# R__(반복 실행)·U__(되돌리기)는 버전이 없거나 짝이 되는 V 와 같은 버전을 갖는다.
# 순서대로 먹이는 이 용도에 섞이면 스키마가 달라지므로 **발견 시 실패**시킨다.
set -euo pipefail

DIR="${1:-src/main/resources/db/migration}"

[ -d "$DIR" ] || { echo "없다: $DIR" >&2; exit 1; }

python3 - "$DIR" <<'PY'
import pathlib, re, sys

d = pathlib.Path(sys.argv[1])

rejected = sorted(p.name for p in d.glob("*.sql") if re.match(r'^[RU]__|^U[0-9]', p.name))
if rejected:
    sys.exit(
        "반복(R__)·되돌리기(U__) 마이그레이션은 이 경로가 다루지 않는다: "
        + ", ".join(rejected)
        + "\n  순서대로 먹이는 방식과 의미가 달라, 실제 Flyway 로 적용해야 한다."
    )

VERSIONED = re.compile(r'^V([0-9]+(?:[._][0-9]+)*)__')

def parts(name: str):
    m = VERSIONED.match(name)
    if not m:
        sys.exit(f"버전을 읽지 못했다: {name}\n  형식은 V<버전>__<설명>.sql 이다.")
    return [int(x) for x in re.split(r'[._]', m.group(1))]

files = sorted(d.glob("V*.sql"), key=lambda p: parts(p.name))

seen: dict[tuple, str] = {}
for p in files:
    k = tuple(parts(p.name))
    if k in seen:
        sys.exit(f"버전이 겹친다: {seen[k]} 와 {p.name}")
    seen[k] = p.name

for p in files:
    print(p)
PY
