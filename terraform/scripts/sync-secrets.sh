#!/usr/bin/env bash
# 로컬 .env 의 비밀을 Secrets Manager 에 동기화한다 (ADR-0013 · ADR-0022).
#
# 키 스키마는 Terraform 이 정본이다 — `terraform output secret_keys` 를 읽는다.
# 여기에 키 목록을 적지 않으므로 locals.secret_keys 에 키를 추가하면 그대로 따라온다.
#
# 키마다 값을 이 순서로 정한다.
#   1. .env 에 같은 이름(대문자)의 값이 있고 자리표시자(change-me*)가 아니면 그 값
#   2. 원격에 CHANGE_ME 가 아닌 값이 이미 있으면 보존
#   3. mysql_root_password · mysql_password · jwt_secret_key 는 생성(openssl rand)
#   4. 그 외는 not-configured (oauth_apple_enabled 만 false)
#
# 단, mysql_root_password · mysql_password 는 2 → 1 → 3 이다. 최초 기동 뒤에 바꾸면 이미 초기화된
# MySQL 과 어긋나 앱이 못 붙으므로, 원격에 값이 있으면 로컬 .env 에 뭐가 있든 원격을 지킨다.
# 정말 바꾸려면 --generate mysql_password 처럼 명시하고 ALTER USER 를 같이 한다(README).
#
# 값은 화면에 찍지 않는다. 키 · 출처 · 길이만 보여준다.
# macOS 기본 bash 3.2 에서 돌아야 하므로 연관 배열·mapfile 을 쓰지 않는다.
#
# 사용:
#   terraform/scripts/sync-secrets.sh [--env-file .env] [--profile P] [--dry-run] [--restart]
#                                     [--generate KEY ...]
#   --generate KEY   .env 나 원격에 값이 있어도 KEY 를 새로 생성한다 (예: jwt_secret_key 분리)
#   --restart        동기화 뒤 EC2 의 pickple 유닛을 재시작해 .env 를 다시 만든다
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TF_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_DIR="$(cd "$TF_DIR/.." && pwd)"

ENV_FILE="$REPO_DIR/.env"
PROFILE=""
DRY_RUN=0
RESTART=0
GENERATE=" "   # 공백으로 구분한 키 목록. 양끝 공백으로 감싸 " key " 검색이 정확히 맞게 한다

while [ $# -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --profile)  PROFILE="$2";  shift 2 ;;
    --dry-run)  DRY_RUN=1;     shift ;;
    --restart)  RESTART=1;     shift ;;
    --generate) GENERATE="$GENERATE$2 "; shift 2 ;;
    -h|--help)  sed -n '2,21p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

for bin in aws jq openssl terraform; do
  command -v "$bin" >/dev/null || { echo "FATAL: $bin 이 필요합니다" >&2; exit 1; }
done
[ -f "$ENV_FILE" ] || { echo "FATAL: $ENV_FILE 이 없습니다" >&2; exit 1; }

# ── Terraform output (정본) ──────────────────────────────────
tf_out() { terraform -chdir="$TF_DIR" output -raw "$1"; }
SECRET_ID="$(tf_out secret_arn)"
REGION="$(tf_out region)"
[ -n "$PROFILE" ] || PROFILE="$(tf_out aws_profile)"
INSTANCE_ID="$(tf_out instance_id)"

# ── 스키마 정본: .env.example (ADR-0026) ─────────────────────
# terraform output 이 아니라 파일을 직접 읽는다. terraform apply 없이도 새 키가 따라온다.
# locals.tf 도 같은 파일을 파싱하므로 둘이 어긋날 수 없다(--check 가 대조한다).
#
# 마커는 키 선언 직전의 "연속 주석 블록" 안 어디든 올 수 있다. 빈 줄이 블록을 끊는다.
# 식별자 문자군에 숫자를 포함한다 — 빠뜨리면 oauth_apple_private_key_base64 가 64 에서 잘린다.
EXAMPLE_FILE="$REPO_DIR/.env.example"
[ -f "$EXAMPLE_FILE" ] || { echo "FATAL: $EXAMPLE_FILE 이 없습니다" >&2; exit 1; }

# key<TAB>generate<TAB>remote_wins<TAB>default 레코드. bash 3.2 라 연관배열을 쓸 수 없다.
POLICY="$(mktemp)"
trap 'rm -f "$POLICY"' EXIT
awk '
  { sub(/\r$/, "") }
  /^[[:space:]]*$/ { blk=""; next }
  /^#?[[:space:]]*(export[[:space:]]+)?[A-Za-z_][A-Za-z0-9_]*=/ {
    line=$0; sub(/^#[[:space:]]*/, "", line); sub(/^export[[:space:]]+/, "", line)
    key=line; sub(/=.*/, "", key)
    if (blk ~ /@secret/) {
      gen=""; if (match(blk, /@generate=[0-9]+/))  gen=substr(blk, RSTART+10, RLENGTH-10)
      def=""; if (match(blk, /@default=[^ \t]+/)) def=substr(blk, RSTART+9,  RLENGTH-9)
      rw = (blk ~ /@remote-wins/) ? "1" : "0"
      print tolower(key) "\t" gen "\t" rw "\t" def
    }
    blk=""; next
  }
  /^#/ { blk = blk " " $0; next }
  { blk="" }
' "$EXAMPLE_FILE" > "$POLICY"

KEYS="$(cut -f1 "$POLICY")"
[ -n "$KEYS" ] || { echo "FATAL: $EXAMPLE_FILE 에서 @secret 키를 찾지 못했습니다" >&2; exit 1; }

# 정책 조회. bash 3.2 호환 — 연관배열 대신 탭 구분 레코드를 grep 한다.
policy_field() { awk -F'\t' -v k="$1" -v n="$2" '$1==k { print $n; exit }' "$POLICY"; }

AWS="aws --region $REGION --profile $PROFILE"

# ── 원격 현재값 ─────────────────────────────────────────────
# 조회 실패는 곧바로 멈춘다(fail-closed). 잘못된 프로필·권한·일시 장애를 "원격 값 없음" 으로
# 오인하면 mysql_* 를 새로 만들어 초기화된 MySQL 과 어긋난다. secret 은 Terraform 이 항상
# 자리표시자 버전과 함께 만들므로 "없음" 이 정상인 경우는 없다. stderr 에는 비밀값이 담기지 않는다.
REMOTE_ERR="$(mktemp)"
if ! REMOTE_JSON="$($AWS secretsmanager get-secret-value --secret-id "$SECRET_ID" \
  --query SecretString --output text 2>"$REMOTE_ERR")"; then
  echo "FATAL: 원격 secret 조회에 실패했습니다. 아무것도 쓰지 않고 멈춥니다." >&2
  cat "$REMOTE_ERR" >&2; rm -f "$REMOTE_ERR"
  exit 1
fi
rm -f "$REMOTE_ERR"
echo "$REMOTE_JSON" | jq -e 'type == "object"' >/dev/null \
  || { echo "FATAL: 원격 secret 이 JSON 객체가 아닙니다" >&2; exit 1; }

# ── 로컬 .env ────────────────────────────────────────────────
# KEY=VALUE 줄만 본다. `export `·주석·빈 줄은 건너뛰고, CRLF 와 감싼 따옴표를 벗긴다.
# 값에 '=' 가 있어도 첫 '=' 까지만 키다. 값은 변수에만 두고 화면에 내지 않는다.
env_get() {
  local line
  line="$(grep -E "^(export )?$1=" "$ENV_FILE" | tail -1 | tr -d '\r' || true)"
  [ -n "$line" ] || return 0
  line="${line#export }"
  local v="${line#*=}"
  v="${v%\"}"; v="${v#\"}"; v="${v%\'}"; v="${v#\'}"
  printf '%s' "$v"
}
env_keys() {
  grep -E '^(export )?[A-Za-z_][A-Za-z0-9_]*=' "$ENV_FILE" | tr -d '\r' \
    | sed -E 's/^export //; s/=.*//' | sort -u
}

is_placeholder() { case "$1" in ''|change-me*|CHANGE_ME) return 0 ;; *) return 1 ;; esac; }
wants_generate() { case "$GENERATE" in *" $1 "*) return 0 ;; *) return 1 ;; esac; }
lower() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }
upper() { printf '%s' "$1" | tr '[:lower:]' '[:upper:]'; }

umask 077
TMP_NEW="$(mktemp)"; TMP_VAL="$(mktemp)"
trap 'rm -f "$TMP_NEW" "$TMP_NEW.next" "$TMP_VAL"' EXIT
echo '{}' > "$TMP_NEW"

printf '\n%-36s %-10s %s\n' "키" "출처" "길이"
printf '%-36s %-10s %s\n' "------------------------------------" "----------" "----"

CHANGED=0
for key in $KEYS; do
  local_val="$(env_get "$(upper "$key")")"
  remote_val="$(echo "$REMOTE_JSON" | jq -r --arg k "$key" '.[$k] // ""')"
  src=""; val=""

  # 정책은 .env.example 의 마커에서 온다(ADR-0026). 키 이름을 여기에 적지 않는다.
  gen_len="$(policy_field "$key" 2)"
  remote_wins="$(policy_field "$key" 3)"
  default_val="$(policy_field "$key" 4)"

  if wants_generate "$key"; then
    src="generated"
  elif [ "$remote_wins" = "1" ]; then
    # @remote-wins — 이미 초기화된 리소스(예: MySQL)의 값을 로컬 .env 가 덮지 못하게 한다.
    if ! is_placeholder "$remote_val"; then src="remote"; val="$remote_val"
    elif ! is_placeholder "$local_val"; then src="env"; val="$local_val"
    fi
  else
    if ! is_placeholder "$local_val"; then src="env"; val="$local_val"
    elif ! is_placeholder "$remote_val"; then src="remote"; val="$remote_val"
    fi
  fi

  if [ -z "$src" ] || [ "$src" = "generated" ]; then
    if [ -n "$gen_len" ]; then
      src="generated"; val="$(openssl rand -base64 "$gen_len")"
    else
      src="default"; val="${default_val:-not-configured}"
    fi
  fi

  [ "$val" != "$remote_val" ] && CHANGED=1
  # 값은 파일로 넘긴다. --arg 로 넘기면 실행 중 ps 에 인자로 보인다.
  printf '%s' "$val" > "$TMP_VAL"
  jq --arg k "$key" --rawfile v "$TMP_VAL" '.[$k] = $v' "$TMP_NEW" > "$TMP_NEW.next" && mv "$TMP_NEW.next" "$TMP_NEW"
  printf '%-36s %-10s %s\n' "$key" "$src" "${#val}"

  # @remote-wins 키가 실제로 바뀌면 알린다 — 이미 초기화된 리소스와 어긋날 수 있다.
  if [ "$remote_wins" = "1" ] && ! is_placeholder "$remote_val" && [ "$val" != "$remote_val" ]; then
    echo "WARN: $key 가 바뀝니다. 이미 초기화된 MySQL 에는 ALTER USER 가 필요합니다 (README 참조)." >&2
  fi
done
rm -f "$TMP_VAL"

# 스키마에 없는 로컬 키는 이름만 알려 준다 (예: 로컬 전용 포트, 아직 스키마에 없는 프로바이더).
IGNORED=""
for k in $(env_keys); do
  lk="$(lower "$k")"
  case " $(echo $KEYS) " in *" $lk "*) ;; *) IGNORED="$IGNORED $k" ;; esac
done
if [ -n "$IGNORED" ]; then
  echo
  echo "스키마에 없어 무시한 .env 키:$IGNORED"
fi

echo
if [ "$CHANGED" -eq 0 ] && [ "$(echo "$REMOTE_JSON" | jq -S .)" = "$(jq -S . "$TMP_NEW")" ]; then
  echo "변경 없음 — 원격과 동일합니다."
  exit 0
fi

if [ "$DRY_RUN" -eq 1 ]; then
  echo "[dry-run] put-secret-value 를 실행하지 않았습니다."
  exit 0
fi

$AWS secretsmanager put-secret-value --secret-id "$SECRET_ID" \
  --secret-string "file://$TMP_NEW" --query VersionId --output text \
  | sed 's/^/put-secret-value VersionId=/'

if [ "$RESTART" -eq 1 ]; then
  echo "EC2 유닛 재시작 (fetch-secrets.sh 가 .env 를 다시 만듭니다)…"
  $AWS ssm send-command --instance-ids "$INSTANCE_ID" \
    --document-name AWS-RunShellScript \
    --parameters 'commands=["systemctl restart pickple"]' \
    --query 'Command.CommandId' --output text | sed 's/^/ssm CommandId=/'
fi
