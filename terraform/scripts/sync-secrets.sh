#!/usr/bin/env bash
# 로컬 .env 의 비밀을 Secrets Manager 에 동기화한다 (ADR-0013 · ADR-0022).
#
# 키 스키마는 Terraform 이 정본이다 — `terraform output secret_keys` 를 읽는다.
# 여기에 키 목록을 적지 않으므로 locals.secret_keys 에 키를 추가하면 그대로 따라온다.
#
# 키마다 값을 이 순서로 정한다.
#   1. .env 에 같은 이름(대문자)의 값이 있고 자리표시자(change-me*)가 아니면 그 값
#   2. 원격에 CHANGE_ME 가 아닌 값이 이미 있으면 보존   ← MySQL 패스워드는 최초 기동 뒤 바꾸면 안 된다
#   3. mysql_root_password · mysql_password · jwt_secret_key 는 생성(openssl rand)
#   4. 그 외는 not-configured (oauth_apple_enabled 만 false)
#
# 값은 화면에 찍지 않는다. 키 · 출처 · 길이만 보여준다.
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
GENERATE=()

while [ $# -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --profile)  PROFILE="$2";  shift 2 ;;
    --dry-run)  DRY_RUN=1;     shift ;;
    --restart)  RESTART=1;     shift ;;
    --generate) GENERATE+=("$2"); shift 2 ;;
    -h|--help)  sed -n '2,20p' "$0"; exit 0 ;;
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
mapfile -t KEYS < <(terraform -chdir="$TF_DIR" output -json secret_keys | jq -r '.[]')
[ "${#KEYS[@]}" -gt 0 ] || { echo "FATAL: secret_keys output 이 비어 있습니다. apply 가 됐는지 확인하십시오" >&2; exit 1; }

AWS=(aws --region "$REGION" --profile "$PROFILE")

# ── 원격 현재값 ─────────────────────────────────────────────
REMOTE_JSON="$("${AWS[@]}" secretsmanager get-secret-value --secret-id "$SECRET_ID" \
  --query SecretString --output text 2>/dev/null || echo '{}')"
echo "$REMOTE_JSON" | jq -e 'type == "object"' >/dev/null \
  || { echo "FATAL: 원격 secret 이 JSON 객체가 아닙니다" >&2; exit 1; }

# ── 로컬 .env ────────────────────────────────────────────────
# KEY=VALUE 만 읽는다. 따옴표는 벗기고, export · 주석 · 빈 줄은 건너뛴다. 값은 변수에만 둔다.
declare -A ENV
while IFS= read -r line || [ -n "$line" ]; do
  line="${line#export }"
  case "$line" in ''|'#'*) continue ;; esac
  [[ "$line" == *=* ]] || continue
  k="${line%%=*}"; v="${line#*=}"
  v="${v%\"}"; v="${v#\"}"; v="${v%\'}"; v="${v#\'}"
  ENV["$k"]="$v"
done < "$ENV_FILE"

is_placeholder() { [[ "$1" == change-me* || "$1" == "CHANGE_ME" || -z "$1" ]]; }
wants_generate() { local k; for k in "${GENERATE[@]:-}"; do [ "$k" = "$1" ] && return 0; done; return 1; }

umask 077
TMP_NEW="$(mktemp)"; trap 'rm -f "$TMP_NEW"' EXIT
echo '{}' > "$TMP_NEW"

printf '\n%-36s %-10s %s\n' "키" "출처" "길이"
printf '%-36s %-10s %s\n' "------------------------------------" "----------" "----"

CHANGED=0
for key in "${KEYS[@]}"; do
  env_name="$(echo "$key" | tr '[:lower:]' '[:upper:]')"
  local_val="${ENV[$env_name]:-}"
  remote_val="$(echo "$REMOTE_JSON" | jq -r --arg k "$key" '.[$k] // ""')"
  src=""; val=""

  if wants_generate "$key"; then
    src="generated"
  elif ! is_placeholder "$local_val"; then
    src="env"; val="$local_val"
  elif ! is_placeholder "$remote_val"; then
    src="remote"; val="$remote_val"
  fi

  if [ -z "$src" ] || [ "$src" = "generated" ]; then
    case "$key" in
      mysql_root_password|mysql_password) src="generated"; val="$(openssl rand -base64 24)" ;;
      jwt_secret_key)                     src="generated"; val="$(openssl rand -base64 48)" ;;
      oauth_apple_enabled)                src="default";   val="false" ;;
      *)                                  src="default";   val="not-configured" ;;
    esac
  fi

  [ "$val" != "$remote_val" ] && CHANGED=1
  jq --arg k "$key" --arg v "$val" '.[$k] = $v' "$TMP_NEW" > "$TMP_NEW.next" && mv "$TMP_NEW.next" "$TMP_NEW"
  printf '%-36s %-10s %s\n' "$key" "$src" "${#val}"
done

# 스키마에 없는 로컬 키는 이름만 알려 준다 (예: 로컬 전용 포트, 아직 스키마에 없는 프로바이더).
IGNORED=()
for k in "${!ENV[@]}"; do
  lk="$(echo "$k" | tr '[:upper:]' '[:lower:]')"
  found=0; for s in "${KEYS[@]}"; do [ "$s" = "$lk" ] && found=1 && break; done
  [ $found -eq 0 ] && IGNORED+=("$k")
done
if [ "${#IGNORED[@]}" -gt 0 ]; then
  echo
  echo "스키마에 없어 무시한 .env 키 (${#IGNORED[@]}): $(printf '%s ' "${IGNORED[@]}" | sort | tr '\n' ' ')"
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

"${AWS[@]}" secretsmanager put-secret-value --secret-id "$SECRET_ID" \
  --secret-string "file://$TMP_NEW" --query VersionId --output text \
  | sed 's/^/put-secret-value VersionId=/'

if echo "$REMOTE_JSON" | jq -e '.mysql_root_password and .mysql_root_password != "CHANGE_ME"' >/dev/null; then
  new_root="$(jq -r .mysql_root_password "$TMP_NEW")"
  old_root="$(echo "$REMOTE_JSON" | jq -r .mysql_root_password)"
  [ "$new_root" != "$old_root" ] && echo "WARN: mysql_root_password 가 바뀌었습니다. 이미 초기화된 MySQL 에는 ALTER USER 가 필요합니다 (README 참조)." >&2
fi

if [ "$RESTART" -eq 1 ]; then
  echo "EC2 유닛 재시작 (fetch-secrets.sh 가 .env 를 다시 만듭니다)…"
  "${AWS[@]}" ssm send-command --instance-ids "$INSTANCE_ID" \
    --document-name AWS-RunShellScript \
    --parameters 'commands=["systemctl restart pickple"]' \
    --query 'Command.CommandId' --output text | sed 's/^/ssm CommandId=/'
fi
