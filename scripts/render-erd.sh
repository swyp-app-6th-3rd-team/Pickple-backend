#!/usr/bin/env bash
# docs/erd/erd.mmd 를 PNG 로 렌더한다.
#
# 결과물은 두 곳이다:
#   docs/erd/erd.png                        리뷰용(diff 에서 보인다)
#   src/main/resources/static/docs/erd.png  서빙용(/docs/erd.png 로 나간다)
#
# mermaid-cli(mmdc) 대신 mermaid.ink 를 쓴다 — mmdc 는 로컬 Chromium 을 요구한다.
# User-Agent 를 반드시 보낸다. 없으면 403 이다.
set -euo pipefail

cd "$(dirname "$0")/.."

SRC="docs/erd/erd.mmd"
OUT_DOCS="docs/erd/erd.png"
OUT_STATIC="src/main/resources/static/docs/erd.png"

[ -f "$SRC" ] || { echo "없다: $SRC" >&2; exit 1; }

mkdir -p "$(dirname "$OUT_STATIC")"

python3 - "$SRC" "$OUT_DOCS" <<'PY'
import base64, json, sys, urllib.request, zlib

src, out = sys.argv[1], sys.argv[2]
code = open(src, encoding="utf-8").read()

payload = json.dumps({
    "code": code,
    "mermaid": {"theme": "dark"},
}).encode()

pako = base64.urlsafe_b64encode(zlib.compress(payload, 9)).decode()

# bgColor 를 붙이면 400 이다(값 형식과 무관하게). 배경은 theme 가 정한다.
# 빈 `%%` 줄도 400 을 만든다 — 주석은 반드시 내용을 갖는다.
url = f"https://mermaid.ink/img/pako:{pako}?type=png"

# mermaid.ink 는 UA 없는 요청을 403 으로 막는다.
req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (pickple-erd-render)"})
data = urllib.request.urlopen(req, timeout=60).read()

if not data.startswith(b"\x89PNG"):
    sys.exit(f"PNG 가 아니다. 앞 80바이트: {data[:80]!r}")

open(out, "wb").write(data)
print(f"{out}  {len(data):,} bytes")
PY

cp "$OUT_DOCS" "$OUT_STATIC"
echo "$OUT_STATIC  (복사됨)"
