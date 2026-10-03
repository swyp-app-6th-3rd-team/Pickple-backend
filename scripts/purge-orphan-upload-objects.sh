#!/bin/bash
set -euo pipefail

# 출시 데이터 초기화(V19, ADR-0053) 뒤 DB 가 더 이상 참조하지 않는 업로드 객체를 지운다.
#
# 왜 Flyway 가 아니라 스크립트인가: 마이그레이션은 DB 안에서만 돈다. item_resource 행이
# 지워져도 S3 객체는 남는다. 버킷 버저닝이 꺼져 있어 삭제는 되돌릴 수 없으므로
# 기본은 dry-run 이고, 실제 삭제는 백업을 먼저 받은 뒤에만 한다.
#
# 대상은 업로드 접두어(product-images/, profile-images/)뿐이다. 기본 프로필(defaults/)은
# terraform 이 관리하므로 나열하지도 않는다.

usage() {
  cat <<'EOF'
Usage:
  scripts/purge-orphan-upload-objects.sh --bucket NAME --referenced-keys FILE [options]

Lists objects under product-images/ and profile-images/, subtracts the keys that the
database still references, and reports the rest. Nothing is deleted without --execute.

Required:
  --bucket NAME              image bucket
  --referenced-keys FILE     item_key values still in item_resource, one per line.
                             Produce it AFTER V19 has been applied:
                               SELECT item_key FROM item_resource;

Options:
  --out-dir DIR              where manifests are written (default: ./purge-<UTC timestamp>)
  --execute                  delete the candidates (requires --backup-dir)
  --backup-dir DIR           download every candidate here before deleting
  --allow-empty-referenced   accept an empty --referenced-keys file (every upload is deleted)
  --endpoint-url URL         passed to aws (LocalStack)
EOF
}

PREFIXES="product-images/ profile-images/"
BUCKET=""
REFERENCED=""
OUT_DIR=""
EXECUTE=false
BACKUP_DIR=""
ALLOW_EMPTY=false
ENDPOINT_ARGS=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --bucket)
      [ "$#" -ge 2 ] || { echo "ERROR: --bucket requires a value" >&2; exit 2; }
      BUCKET="$2"; shift 2 ;;
    --referenced-keys)
      [ "$#" -ge 2 ] || { echo "ERROR: --referenced-keys requires a path" >&2; exit 2; }
      REFERENCED="$2"; shift 2 ;;
    --out-dir)
      [ "$#" -ge 2 ] || { echo "ERROR: --out-dir requires a path" >&2; exit 2; }
      OUT_DIR="$2"; shift 2 ;;
    --backup-dir)
      [ "$#" -ge 2 ] || { echo "ERROR: --backup-dir requires a path" >&2; exit 2; }
      BACKUP_DIR="$2"; shift 2 ;;
    --endpoint-url)
      [ "$#" -ge 2 ] || { echo "ERROR: --endpoint-url requires a value" >&2; exit 2; }
      ENDPOINT_ARGS="--endpoint-url $2"; shift 2 ;;
    --execute) EXECUTE=true; shift ;;
    --allow-empty-referenced) ALLOW_EMPTY=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "ERROR: unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

# 로케일을 고정한다. sort·comm 의 정렬 기준이 환경마다 다르면 차집합이 틀어진다.
export LC_ALL=C

[ -n "$BUCKET" ] || { echo "ERROR: --bucket is required" >&2; exit 2; }
[ -n "$REFERENCED" ] || { echo "ERROR: --referenced-keys is required" >&2; exit 2; }
[ -f "$REFERENCED" ] || { echo "ERROR: referenced keys file not found: $REFERENCED" >&2; exit 2; }
command -v aws >/dev/null || { echo "ERROR: aws CLI not found" >&2; exit 2; }

# 빈 파일은 "참조가 하나도 없다" 와 "조회에 실패했다" 를 구분할 수 없다. 명시해야만 받는다.
if [ ! -s "$REFERENCED" ] && [ "$ALLOW_EMPTY" != true ]; then
  echo "ERROR: $REFERENCED is empty. Every upload would be deleted." >&2
  echo "       Re-run the query, or pass --allow-empty-referenced if that is intended." >&2
  exit 2
fi
if [ "$EXECUTE" = true ] && [ -z "$BACKUP_DIR" ]; then
  echo "ERROR: --execute requires --backup-dir (bucket versioning is off; deletes are permanent)" >&2
  exit 2
fi

OUT_DIR="${OUT_DIR:-./purge-$(date -u +%Y%m%dT%H%M%SZ)}"
mkdir -p "$OUT_DIR"
LISTED="$OUT_DIR/listed.txt"
KEEP="$OUT_DIR/referenced.txt"
CANDIDATES="$OUT_DIR/candidates.txt"

: > "$LISTED"
for prefix in $PREFIXES; do
  # --output text 는 페이지를 자동으로 이어 받는다. 결과가 없으면 "None" 한 줄이 나온다.
  # aws 를 파이프 앞에 두지 않는다 — 뒤의 `|| true` 가 나열 실패(버킷 오타·권한)까지 삼켜
  # "후보 0건" 으로 조용히 끝나기 때문이다.
  raw="$OUT_DIR/.list-${prefix%/}.txt"
  # shellcheck disable=SC2086
  if ! aws $ENDPOINT_ARGS s3api list-objects-v2 --bucket "$BUCKET" --prefix "$prefix" \
      --query 'Contents[].Key' --output text > "$raw"; then
    echo "ERROR: failed to list s3://$BUCKET/$prefix" >&2
    exit 1
  fi
  tr '\t' '\n' < "$raw" | grep -v '^None$' | grep -v '^$' >> "$LISTED" || true
  rm -f "$raw"
done
sort -u -o "$LISTED" "$LISTED"
tr -d '\r' < "$REFERENCED" | grep -v '^$' | sort -u > "$KEEP" || true

# 나열한 것 중 참조되지 않는 것. 접두어 밖의 키는 목록에 섞여도 후보가 되지 않는다.
comm -23 "$LISTED" "$KEEP" | grep -E '^(product-images|profile-images)/' > "$CANDIDATES" || true

listed_n=$(wc -l < "$LISTED" | tr -d ' ')
keep_n=$(wc -l < "$KEEP" | tr -d ' ')
cand_n=$(wc -l < "$CANDIDATES" | tr -d ' ')
echo "bucket=$BUCKET listed=$listed_n referenced=$keep_n candidates=$cand_n"
echo "manifest: $CANDIDATES"
head -n 20 "$CANDIDATES" | sed 's/^/  /'
[ "$cand_n" -gt 20 ] && echo "  ... ($((cand_n - 20)) more)"

if [ "$EXECUTE" != true ]; then
  echo "dry-run: nothing deleted. Re-run with --execute --backup-dir DIR to delete."
  exit 0
fi
[ "$cand_n" -gt 0 ] || { echo "nothing to delete"; exit 0; }

# 백업을 전부 받은 뒤에만 지운다. 하나라도 실패하면 삭제를 시작하지 않는다.
mkdir -p "$BACKUP_DIR"
while IFS= read -r key; do
  # shellcheck disable=SC2086
  aws $ENDPOINT_ARGS s3 cp --only-show-errors "s3://$BUCKET/$key" "$BACKUP_DIR/$key"
done < "$CANDIDATES"
backed_n=$(cd "$BACKUP_DIR" && find product-images profile-images -type f 2>/dev/null | wc -l | tr -d ' ')
if [ "$backed_n" -lt "$cand_n" ]; then
  echo "ERROR: backup has $backed_n of $cand_n objects. Nothing deleted." >&2
  exit 1
fi
echo "backup: $backed_n objects in $BACKUP_DIR"

failed=0
: > "$OUT_DIR/deleted.txt"
while IFS= read -r key; do
  # shellcheck disable=SC2086
  if aws $ENDPOINT_ARGS s3 rm --only-show-errors "s3://$BUCKET/$key"; then
    echo "$key" >> "$OUT_DIR/deleted.txt"
  else
    failed=$((failed + 1))
    echo "$key" >> "$OUT_DIR/failed.txt"
  fi
done < "$CANDIDATES"
echo "deleted=$(wc -l < "$OUT_DIR/deleted.txt" | tr -d ' ') failed=$failed"
[ "$failed" -eq 0 ] || exit 1
