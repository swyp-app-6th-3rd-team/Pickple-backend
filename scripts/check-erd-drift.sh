#!/usr/bin/env bash
# Flyway 가 만든 실제 스키마와 docs/erd/erd.mmd 를 대조해 드리프트를 잡는다.
#
#   scripts/check-erd-drift.sh            CI 용. 스키마를 MYSQL_* 환경변수가 가리키는 DB 에서 읽는다.
#   scripts/check-erd-drift.sh --local    로컬 pickple-mysql 컨테이너에서 읽는다.
#
# 왜 엔티티가 아니라 스키마를 정본으로 삼는가:
#   erd.mmd 는 "정본은 JPA 엔티티다(ddl-auto: validate 로 보증)" 라고 적고 있지만,
#   validate 는 *엔티티가 선언한* 컬럼이 DB 에 있는지만 본다. DB 에만 있고 엔티티에 없는
#   컬럼은 검사 대상이 아니다 — 실제로 users.ranking·highest_grade·point 가 그렇게
#   ERD 에서 빠진 채 남아 있었다(V7·V8 이 ALTER 로 넣고 네이티브 쿼리로만 읽는다).
#   그래서 보증의 방향이 한쪽뿐이고, 반대 방향은 이 스크립트가 채운다.
#
# 검사하지 않는 것(의도적):
#   - 자료형·길이·nullable. ddl-auto: validate 가 이미 엔티티 매핑분을 잡는다.
#   - 관계선. FK 제약과 일치하지 않는다 — 애그리거트 경계를 넘는 참조는 FK 를 걸지
#     않기 때문이다(ADR-0008). 여기서 대조하면 정상인 설계가 실패로 뜬다.
#   - 논리 ERD(erd-logical.mmd). 한글 엔티티명이라 테이블명과 직접 매칭되지 않는다.
#     테이블 *개수* 만 물리 ERD 와 맞는지 본다.
set -euo pipefail

cd "$(dirname "$0")/.."

ERD="docs/erd/erd.mmd"
ERD_LOGICAL="docs/erd/erd-logical.mmd"

if [[ "${1:-}" == "--local" ]]; then
  DB="${MYSQL_DATABASE:-pickple}"
  # 비밀번호를 여기 적지 않는다. 컨테이너가 들고 있는 값을 그대로 읽는다.
  LOCAL_PW="$(docker exec pickple-mysql printenv MYSQL_ROOT_PASSWORD)"
  runsql() { docker exec -i pickple-mysql mysql -uroot -p"$LOCAL_PW" -N -B -e "$1" 2>/dev/null; }
else
  DB="${MYSQL_DATABASE:-pickple}"
  runsql() { mysql -h "${MYSQL_HOST:-127.0.0.1}" -P "${MYSQL_PORT:-3306}" \
      -u"${MYSQL_USER:-root}" -p"${MYSQL_PASSWORD:-root}" -N -B -e "$1"; }
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# 실제 스키마: flyway_schema_history 는 ERD 대상이 아니라 뺀다.
runsql "SELECT CONCAT(table_name, '.', column_name)
        FROM information_schema.columns
        WHERE table_schema = '$DB' AND table_name <> 'flyway_schema_history'
        ORDER BY table_name, column_name;" | sort > "$tmp/db.txt"

if [[ ! -s "$tmp/db.txt" ]]; then
  echo "::error::스키마를 읽지 못했다. DB='$DB' 에 Flyway 가 적용됐는지 확인한다."
  exit 1
fi

# ERD 선언: "    table {" 블록 안의 "    자료형 컬럼명 ..." 줄에서 컬럼명을 뽑는다.
awk '
  /^    [a-z_]+ \{/ { t = $1; next }
  /^    \}/         { t = ""; next }
  t && NF >= 2 && $1 !~ /^%%/ { print t "." $2 }
' "$ERD" | sort > "$tmp/erd.txt"

missing="$(comm -23 "$tmp/db.txt" "$tmp/erd.txt")"   # DB 에 있는데 ERD 에 없다
extra="$(comm -13 "$tmp/db.txt" "$tmp/erd.txt")"     # ERD 에 있는데 DB 에 없다

status=0

if [[ -n "$missing" ]]; then
  echo "::error::스키마에 있는데 $ERD 에 없는 컬럼:"
  echo "$missing" | sed 's/^/  + /'
  status=1
fi

if [[ -n "$extra" ]]; then
  echo "::error::$ERD 에 있는데 스키마에 없는 컬럼:"
  echo "$extra" | sed 's/^/  - /'
  status=1
fi

# 논리 ERD 는 이름이 한글이라 컬럼 대조를 못 한다. 엔티티 개수만 맞춘다 —
# 테이블이 새로 생겼는데 논리 ERD 에만 안 넣는 흔한 누락을 잡는다.
phys_count="$(grep -cE '^    [a-z_]+ \{' "$ERD")"
logi_count="$(grep -cE '^    [가-힣]+ \{' "$ERD_LOGICAL")"
if [[ "$phys_count" != "$logi_count" ]]; then
  echo "::error::엔티티 개수가 다르다 — $ERD $phys_count 개, $ERD_LOGICAL $logi_count 개."
  status=1
fi

# archify 상세 페이지의 컴포넌트는 sublabel 에 실제 테이블명을 달고 있다.
# 여기서는 이름까지 대조할 수 있으므로 개수가 아니라 집합으로 본다.
ARCHIFY_SPEC="docs/erd/erd-logical.architecture.json"
python3 -c "
import json, sys
spec = json.load(open('$ARCHIFY_SPEC', encoding='utf-8'))
print('\n'.join(sorted(c.get('sublabel','') for c in spec['components'])))
" | sort > "$tmp/archify.txt"
cut -d. -f1 < "$tmp/db.txt" | sort -u > "$tmp/db-tables.txt"
if ! diff -q "$tmp/db-tables.txt" "$tmp/archify.txt" >/dev/null; then
  echo "::error::$ARCHIFY_SPEC 의 테이블이 스키마와 다르다:"
  diff "$tmp/db-tables.txt" "$tmp/archify.txt" | sed 's/^/  /'
  status=1
fi

# .mmd 를 고치고 렌더를 안 하면 문서에 옛 그림이 나간다. 소스가 산출물보다 새로우면 실패.
for pair in "docs/erd/erd.mmd:docs/erd/erd.svg" \
            "docs/erd/erd-logical.mmd:src/main/resources/static/docs/erd.svg" \
            "docs/erd/erd-logical.architecture.json:src/main/resources/static/docs/erd.html"; do
  src="${pair%%:*}"; out="${pair##*:}"
  if [[ ! -f "$out" ]]; then
    echo "::error::$out 이 없다. scripts/render-erd.sh 를 실행한다."
    status=1
  elif [[ "$src" -nt "$out" ]]; then
    echo "::error::$src 가 $out 보다 새롭다. scripts/render-erd.sh 로 다시 렌더한다."
    status=1
  fi
done

if [[ "$status" == 0 ]]; then
  echo "ERD 정합 확인 — 테이블 $phys_count 개, 컬럼 $(wc -l < "$tmp/db.txt" | tr -d ' ') 개 일치."
fi

exit "$status"
