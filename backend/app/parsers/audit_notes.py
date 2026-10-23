"""감사보고서 주석 본문에서 주주현황 표를 뽑는다(비상장 외감법인의 최대주주 추정용, 순수 함수).

주석 '일반사항'에는 보통 "주주현황은 다음과 같습니다. 주 주 명 주 식 수 지 분 율 (주)보이저홀딩스 26,000,000 주 100.00% 합 계 26,000,000 주 100.00%" 형태로 들어 있다(2026-10 라이프자산운용 실데이터).
표 모양은 회사마다 달라 못 찾으면 빈 목록을 돌려준다.
"""
from __future__ import annotations

import html as _html
import re

_HEADER_RE = re.compile(r"주\s*주\s*명\s*주\s*식\s*수\s*지\s*분\s*율")
_ROW_RE = re.compile(r"(?P<name>.{1,40}?)\s+(?P<shares>\d[\d,]*)\s*주\s+(?P<ratio>\d+(?:\.\d+)?)\s*%")


def html_to_text(raw: str) -> str:
    text = re.sub(r"<[^>]+>", " ", raw)
    for _ in range(2):  # 응답에 &amp;nbsp; 처럼 이중 인코딩된 공백이 섞여 온다
        text = _html.unescape(text)
    return re.sub(r"\s+", " ", text.replace("\xa0", " ")).strip()


def extract_holders(text: str) -> dict | None:
    """{"holders": [{name, shares, ratio}], "total_ratio": float|None} 또는 None."""
    m = _HEADER_RE.search(text)
    if not m:
        return None
    rest = text[m.end():m.end() + 1500]
    holders: list[dict] = []
    total_ratio: float | None = None
    pos = 0
    while True:
        row = _ROW_RE.match(rest, pos) or _ROW_RE.search(rest, pos)
        if not row or row.start() - pos > 20:
            break
        name = row["name"].strip()
        shares = int(row["shares"].replace(",", ""))
        ratio = float(row["ratio"])
        pos = row.end()
        if re.sub(r"\s", "", name) in ("합계", "계", "총계"):
            total_ratio = ratio
            break
        holders.append({"name": name, "shares": shares, "ratio": ratio})
    if not holders:
        return None
    if total_ratio is None:
        total_ratio = round(sum(h["ratio"] for h in holders), 2)
    return {"holders": holders, "total_ratio": total_ratio}
