import fitz, re, sys, os, glob

# Notion PDF 내보내기는 셀을 2~3자 단위로 하드랩한다.
# 줄 단위 재조립으로는 문장이 복원되지 않으므로,
# 컬럼 그리드로 셀을 다시 묶고 랩을 제거한 뒤 구조를 복원한다.
COLS = [10, 72, 111, 159, 207, 255, 332, 380, 428, 477, 524, 546]
FIELDS = ["기능그룹", "주요기능", "트리거", "상세기능", "동작/로직",
          "동작/로직", "예외케이스", "알림/메시지", "우선순위", "비고"]

def colof(bbox):
    """span 의 중심 x 로 컬럼을 정한다. 좌상단만 보면 경계에 걸친 글자가 갈린다."""
    x = (bbox[0] + bbox[2]) / 2
    for i in range(len(COLS) - 1):
        if COLS[i] <= x < COLS[i + 1]:
            return i
    return None

def cell_texts(page):
    """컬럼별로 span 을 모아 하드랩을 제거한 문자열을 돌려준다."""
    buckets = {}
    for blk in page.get_text("dict")["blocks"]:
        for ln in blk.get("lines", []):
            for sp in ln["spans"]:
                c = colof(sp["bbox"])
                if c is None:
                    continue
                buckets.setdefault(c, []).append(sp["text"])
    # 문서 순서가 곧 읽기 순서다. 좌표로 재정렬하면 구두점이 단어 사이로 끼어든다.
    return {c: "".join(v) for c, v in buckets.items()}

def tidy(s):
    """붙어버린 구두점과 불릿을 사람이 읽는 형태로 되돌린다."""
    if not s:
        return ""
    s = s.replace("\u200b", "")
    s = re.sub(r"[ \t]+", " ", s)
    s = re.sub(r"\s+([,.)\]%])", r"\1", s)
    s = re.sub(r"([(\[])\s+", r"\1", s)
    # 불릿·번호 앞에서 줄을 나눈다
    s = re.sub(r"•", "\n- ", s)
    s = re.sub(r"\[\s*([^\]]{1,20}?)\s*\]", r"\n\n**[\1]**\n", s)
    s = re.sub(r"<\s*([^>]{1,30}?)\s*>", r"\n\n**<\1>**\n", s)
    s = re.sub(r"(?<![0-9])([1-9])\.(?=[가-힣A-Za-z])", r"\n\1. ", s)
    # 문장 끝 마침표 뒤 줄바꿈
    s = re.sub(r"다\.(?=[가-힣])", "다.\n", s)
    s = re.sub(r"\n{3,}", "\n\n", s)
    return s.strip()

def label(s):
    """조치·상황 같은 라벨을 굵게."""
    return re.sub(r"(상황|조치|처리|사용자안내|기본동작|분기처리)\s*:",
                  lambda m: f"**{m.group(1)}**: ", s)

def page_md(page, n):
    cells = cell_texts(page)
    parts = []
    for c in sorted(cells):
        txt = cells[c]
        if c == 0:
            # 컬럼 0 은 대개 페이지 푸터("기능명세서 N")지만,
            # 셀이 넘칠 때 본문 조각이 흘러들어오기도 한다. 푸터만 떼고 나머지는 살린다.
            txt = re.sub(r"기능명세서\s*\d*|정책\s*요약표\s*\d*", "", txt).strip()
            if not txt:
                continue
        # 헤더 반복 제거
        # 헤더 토큰만 정확히 떼어낸다. 본문이 뒤에 붙어 있으면 본문은 남긴다.
        txt = re.sub(r"^\s*(?:기능\s*그룹|주요\s*기능|트리\s*거|상세\s*기능|"
                     r"동작\s*/?\s*로\s*직|예외\s*케이스|알림\s*/?\s*메시지|"
                     r"우선\s*순위|비고|기능명세서)\s*", "", txt)
        txt = txt.strip()
        if not txt or txt.isdigit():
            continue
        name = FIELDS[c - 1] if 1 <= c <= len(FIELDS) else f"col{c}"
        parts.append((name, label(tidy(txt))))
    if not parts:
        return ""
    buf = [f"\n## p.{n}\n"]
    for name, txt in parts:
        if "\n" in txt:
            buf.append(f"**{name}**\n\n{txt}\n")
        else:
            buf.append(f"**{name}** — {txt}\n")
    return "\n".join(buf)

def convert(pdf):
    d = fitz.open(pdf)
    name = os.path.basename(pdf)[:-4]
    out = pdf[:-4] + ".md"
    enc = name.replace(" ", "%20")
    with open(out, "w", encoding="utf-8") as fh:
        fh.write(f"# {name}\n\n")
        fh.write(f"> 원문은 같은 폴더의 [`{name}.pdf`](./{enc}.pdf) 다. "
                 f"이 파일은 `git diff` 와 `grep` 을 위한 **파생물이며 정본이 아니다.**\n>\n")
        fh.write(f"> 원문 PDF 는 표 셀을 2~3자 단위로 하드랩해 내보낸다. "
                 f"여기서는 컬럼 그리드로 셀을 다시 묶어 줄바꿈을 제거하고 불릿·라벨 구조를 복원했다. "
                 f"**따라서 줄바꿈 위치는 원문과 다르다** — 인용할 때는 PDF 를 본다.\n>\n")
        fh.write(f"> 총 {d.page_count}페이지. 재생성은 [README](./README.md) 참고.\n")
        for i, p in enumerate(d):
            fh.write(page_md(p, i + 1))
    return out, d.page_count, os.path.getsize(out)

for pdf in sorted(glob.glob("*.pdf")):
    print("%-24s %2dp %6dB" % convert(pdf)[:1] + ("",) if False else "%-24s %2dp %6dB" % convert(pdf))
