#!/usr/bin/env bash
# docs/erd/*.mmd 를 SVG 로 렌더한다.
#
# 결과물:
#   docs/erd/erd.svg                         물리 ERD, 리뷰용(diff 에서 보인다)
#   docs/erd/erd-logical.svg                 논리 ERD, 리뷰용
#   src/main/resources/static/docs/erd.svg   서빙용(/docs/erd.svg 로 나간다 — Scalar 가 싣는 그림)
#
# PNG 가 아니라 SVG 인 이유: PNG 는 래스터라 확대하면 뭉갠다. 실측으로 서빙 PNG 는
# 1904x1058 이었는데 Mermaid 원본 레이아웃은 4600x2557 이다 — 41% 로 축소해 구운 셈이다.
#
# mermaid-cli(mmdc) 대신 mermaid.ink 를 쓴다 — mmdc 는 로컬 Chromium 을 요구한다.
#
# mermaid.ink 함정 넷. 전부 실제로 밟아봤다:
#   1. User-Agent 를 안 보내면 403 이다.
#   2. bgColor 파라미터를 붙이면 400 이다(값 형식과 무관하게). 배경은 themeVariables 로 정한다.
#   3. 빈 `%%` 줄도 400 을 만든다 — 주석은 반드시 내용을 갖는다.
#   4. 기본 SVG 는 텍스트를 <foreignObject> 로 낸다(<text> 0개). <img> 로 임베드된 SVG 는
#      이미지 모드라 foreignObject 를 렌더하지 않아 글자가 전부 사라진다.
#      최상위 htmlLabels:false 를 줘야 <text> 로 바뀐다. er.htmlLabels 는 먹지 않는다.
set -euo pipefail

cd "$(dirname "$0")/.."

# macOS 는 shasum, 리눅스(러너)는 sha256sum 이다. 한쪽만 쓰면 다른 쪽에서 127 로 죽는다.
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

# --check: 렌더하지 않고 "어느 산출물이 낡았는지"만 알린다. archify 가 없는 사람도
# 자기 변경이 무엇을 낡게 만들었는지 확인할 수 있다(고칠 수는 없어도 알 수는 있게).
if [[ "${1:-}" == "--check" ]]; then
  rc=0
  for pair in "docs/erd/erd.mmd:docs/erd/erd.svg" \
              "docs/erd/erd-logical.mmd:src/main/resources/static/docs/erd.svg" \
              "docs/erd/erd-logical.architecture.json:src/main/resources/static/docs/erd.html"; do
    src="${pair%%:*}"; out="${pair##*:}"
    want="$(sha256_of "$src")"
    got="$(grep -o 'erd-source-sha256:[[:xdigit:]]\{64\}' "$out" 2>/dev/null | head -1 | cut -d: -f2 || true)"
    if [[ "$want" == "$got" ]]; then
      echo "최신  $out"
    else
      echo "낡음  $out  <- $src"
      rc=1
    fi
  done
  exit "$rc"
fi

OUT_STATIC_DIR="src/main/resources/static/docs"
mkdir -p "$OUT_STATIC_DIR"

python3 - <<'PY'
import base64, hashlib, json, pathlib, re, sys, urllib.request, zlib

# 테마 중립 팔레트.
#
# Scalar 는 다크·라이트 토글을 갖는데, <img> 로 들어간 SVG 는 그 토글 상태를 알 수 없다.
# (prefers-color-scheme 은 문서가 아니라 OS 설정을 따르므로 Scalar 토글과 어긋난다.)
# 그래서 테마를 따라가려 하지 않고, 자기 배경을 가진 밝은 카드로 굳힌다 —
# 어느 배경 위에 놓여도 대비가 보장되는 유일한 방법이다.
#
# 색은 넷을 넘기지 않는다. 애그리거트 경계를 나타내고, 그 이상은 의미를 잃는다.
SURFACE = "#f7f8fa"   # 도면 배경 — 순백 대신 옅은 회색이라 다크 모드에서 덜 눈부시다
INK     = "#1f2933"   # 글자·테두리
ACCENT  = "#2f6f9f"   # 엔티티 머리글 (차분한 청색)
MUTED   = "#e4e8ee"   # 속성 행 배경

THEME_VARIABLES = {
    "background":        SURFACE,
    "primaryColor":      MUTED,
    "primaryTextColor":  INK,
    "primaryBorderColor": ACCENT,
    "lineColor":         INK,
    "textColor":         INK,
    "fontFamily": "Pretendard, Apple SD Gothic Neo, Noto Sans KR, sans-serif",
    "fontSize": "16px",
    # er 다이어그램 전용 — 머리글은 진하게, 속성 행은 옅게 번갈아 둔다.
    "attributeBackgroundColorOdd":  SURFACE,
    "attributeBackgroundColorEven": MUTED,
}

TARGETS = [
    ("docs/erd/erd.mmd",         "docs/erd/erd.svg",         None),
    ("docs/erd/erd-logical.mmd", "docs/erd/erd-logical.svg", "src/main/resources/static/docs/erd.svg"),
]

def render(src: str, out: str, serve: str | None) -> None:
    code = pathlib.Path(src).read_text(encoding="utf-8")
    payload = json.dumps({
        "code": code,
        "mermaid": {
            "theme": "base",
            "themeVariables": THEME_VARIABLES,
            # 이걸 빼면 <img> 에서 글자가 통째로 사라진다. 위 주석 4번.
            "htmlLabels": False,
        },
    }).encode()
    pako = base64.urlsafe_b64encode(zlib.compress(payload, 9)).decode()
    url = f"https://mermaid.ink/svg/pako:{pako}"

    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (pickple-erd-render)"})
    svg = urllib.request.urlopen(req, timeout=60).read().decode("utf-8")

    if "<svg" not in svg:
        sys.exit(f"SVG 가 아니다: {svg[:200]!r}")

    # 렌더 결과가 진짜 벡터 텍스트인지 확인한다. foreignObject 로 돌아가면
    # <img> 에서 글자가 사라지므로, 통과가 아니라 실패로 알린다.
    if "<foreignObject" in svg or "<text" not in svg:
        sys.exit(f"{src}: 텍스트가 <text> 가 아니다. htmlLabels 설정을 확인한다.")

    # width="100%" 는 컨테이너에 따라 찌그러진다. viewBox 비율을 그대로 쓰도록 고정 폭을 준다.
    m = re.search(r'viewBox="0 0 ([\d.]+) ([\d.]+)"', svg)
    if not m:
        sys.exit(f"{src}: viewBox 를 찾지 못했다.")
    w, h = float(m.group(1)), float(m.group(2))
    svg = svg.replace('width="100%"', f'width="{w:.0f}" height="{h:.0f}"', 1)

    # 배경을 명시적으로 깐다. themeVariables 의 background 는 도형 밖 여백까지
    # 칠하지 않는 경우가 있어, 투명 배경이 남으면 다크 모드에서 글자가 묻힌다.
    rect = f'<rect width="100%" height="100%" fill="{SURFACE}"/>'
    svg = re.sub(r'(<svg[^>]*>)', r'\1' + rect, svg, count=1)

    # 소스 해시를 산출물에 새긴다. check-erd-drift.sh 가 이 값으로 신선도를 판정한다 —
    # mtime 은 git 체크아웃이 전부 같은 값으로 만들어 CI 에서 무력하다.
    digest = hashlib.sha256(pathlib.Path(src).read_bytes()).hexdigest()
    svg = svg.replace("<svg", f"<!-- erd-source-sha256:{digest} -->\n<svg", 1)

    pathlib.Path(out).write_text(svg, encoding="utf-8")
    print(f"{out}  {len(svg):,} bytes  ({w:.0f}x{h:.0f}, <text> {svg.count('<text')}개)")

    if serve:
        pathlib.Path(serve).write_text(svg, encoding="utf-8")
        print(f"{serve}  (서빙용 복사)")

for src, out, serve in TARGETS:
    render(src, out, serve)
PY

# ── 여기부터는 재현 조건이 다르다 ───────────────────────────────────────────
# 위 SVG 렌더는 네트워크(mermaid.ink)만 있으면 누구나·CI 도 돌릴 수 있다.
# 아래 archify 단계는 **개인 스킬 설치본**($HOME/.claude/skills)에 의존한다.
# 저장소만 클론한 사람은 이 단계를 돌릴 수 없으므로, 없으면 조용히 건너뛰고
# SVG 만 갱신한다(에러 아님).
#
# 다만 CI 의 erd-drift 는 erd.html 도 검사한다 — DocsConfig 가 이 페이지를 "실제로 읽을
# 때 여는 정본" 으로 안내해서, 낡으면 독자가 옛 그림을 보기 때문이다. 그래서 archify 가
# 없는데 spec 을 고치면 CI 가 막힌다. 그때는 spec(JSON)만 고쳐 커밋하고 archify 보유자에게
# 렌더를 요청한다(README 의 ERD 절). `--check` 로 무엇이 낡았는지는 archify 없이도 본다.
#
# 확대·팬·테마 전환이 되는 상세 페이지. Scalar 본문에서 "전체 화면으로 열기" 로 연다.
# Mermaid 의 ER 레이아웃은 가로 폭을 못 줄인다 — 논리 ERD 도 4364px 라 Scalar 소개
# 칼럼(실측 470px)에 넣으면 글자를 읽을 수 없다. 그래서 상세 페이지를 따로 만든다.
# 색은 애그리거트 경계 넷(회원·게시글·참여·지원)에만 쓴다.
#
# 출력 경로를 바꾸면 DocsConfig.DESCRIPTION 의 마크다운 링크도 함께 고친다.
ARCHIFY="$HOME/.claude/skills/archify/bin/archify.mjs"
SPEC="docs/erd/erd-logical.architecture.json"
HTML="src/main/resources/static/docs/erd.html"

if [ ! -f "$ARCHIFY" ]; then
  echo "건너뜀: archify 가 없다($ARCHIFY). SVG 만 갱신했다." >&2
  exit 0
fi

# nvm 지연 로딩 스텁이 비대화형 셸에서 node 를 가린다. 실제 바이너리를 직접 찾는다.
NODE_BIN="$(command -v node 2>/dev/null || true)"
if [ -z "$NODE_BIN" ] || ! "$NODE_BIN" --version >/dev/null 2>&1; then
  NODE_BIN="$(ls -d "$HOME"/.nvm/versions/node/*/bin/node 2>/dev/null | tail -1)"
fi
if [ -z "$NODE_BIN" ]; then
  echo "건너뜀: node 를 찾지 못했다. SVG 만 갱신했다." >&2
  exit 0
fi

"$NODE_BIN" "$ARCHIFY" deliver architecture "$SPEC" "$HTML" --quality showcase
# visual-check 가 산출물 옆에 스크린샷·접촉 시트를 떨군다. 서빙 디렉터리에 남기지 않는다.
rm -f src/main/resources/static/docs/erd.visual-check.*

# SVG 와 같은 이유로 소스 해시를 새긴다. archify 는 산출물을 서명하므로 덮어쓰지 않고
# 주석 한 줄만 덧붙인다 — 렌더 내용은 건드리지 않는다.
printf '\n<!-- erd-source-sha256:%s -->\n' "$(sha256_of "$SPEC")" >> "$HTML"
echo "$HTML  (archify 상세 페이지)"
