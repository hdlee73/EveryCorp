"""CB·BW 잔액 계산용 순수 함수.

1) 발행결정 공시(cvbdIsDecsn·bdwtIsDecsn) 행 → 발행 건 목록(권면총액·납입일·만기일). 전환·상환이 반영되지 않은 발행 당시 값이다.
2) 사업보고서의 '전환사채 발행현황'·'신주인수권부사채 발행현황' 표 → 보고서 기준 미상환(미전환) 잔액 합계.
   표 모양은 회사마다 달라 못 찾으면 None을 돌려주고, 호출하는 쪽이 발행결정 기준 추정으로 대신한다.
"""
from __future__ import annotations

import re

from bs4 import BeautifulSoup


def digits(s: object) -> str:
    return re.sub(r"\D", "", str(s or ""))


def to_int(s: object) -> int | None:
    t = re.sub(r"[,\s]", "", str(s or ""))
    t = t.replace("△", "-")
    if not re.fullmatch(r"-?\d+", t):
        return None
    return int(t)


def parse_decisions(rows: list[dict], kind: str) -> list[dict]:
    """발행결정 API 행 → [{kind, tm, issued, maturity, amount}]. 같은 회차의 정정 공시는 하나로 합친다(뒤에 온 값 우선)."""
    seen: dict[tuple, dict] = {}
    for r in rows:
        amount = to_int(r.get("bd_fta"))
        maturity = digits(r.get("bd_mtd"))
        if amount is None and not maturity:
            continue
        tm = (r.get("bd_tm") or "").strip()
        issued = digits(r.get("pymd"))
        seen[(kind, tm, issued)] = {"kind": kind, "tm": tm, "issued": issued, "maturity": maturity, "amount": amount or 0}
    return list(seen.values())


def live_decisions(bonds: list[dict], today: str) -> list[dict]:
    """납입이 끝났고(납입일 ≤ today) 만기(YYYYMMDD)가 today 이후이거나 비어 있는 발행 건."""
    return [b for b in bonds
            if (not b["issued"] or b["issued"][:8] <= today) and (not b["maturity"] or b["maturity"][:8] >= today)]


_UNIT_MULT = (("백만원", 1_000_000), ("천원", 1_000), ("억원", 100_000_000), ("만원", 10_000), ("원", 1))
_BAL_WORDS = ("미상환", "미전환", "미행사", "잔액", "잔여")
_AMT_WORDS = ("권면", "금액", "총액", "잔액")


def _unit(text: str) -> int | None:
    m = re.search(r"단위\s*[:：]?\s*([가-힣]*원)", text)
    if not m:
        return None
    return next((mult for word, mult in _UNIT_MULT if m.group(1).endswith(word)), None)


def _grid(table) -> list[list[str]]:
    """rowspan·colspan을 펼친 표."""
    grid: list[list[str]] = []
    for ri, tr in enumerate(table.find_all("tr")):
        while len(grid) <= ri:
            grid.append([])
        c = 0
        for cell in tr.find_all(["th", "td"]):
            while c < len(grid[ri]) and grid[ri][c] is not None:
                c += 1
            text = cell.get_text(" ", strip=True)
            rs, cs = int(cell.get("rowspan", 1) or 1), int(cell.get("colspan", 1) or 1)
            for dr in range(min(rs, 8)):
                while len(grid) <= ri + dr:
                    grid.append([])
                row = grid[ri + dr]
                while len(row) < c + cs:
                    row.append(None)
                for dc in range(min(cs, 30)):
                    row[c + dc] = text
            c += cs
    return [[x or "" for x in r] for r in grid if any(r)]


def _find_col(heads: list[str], *words: str) -> int | None:
    return next((j for j, h in enumerate(heads) if any(w in h for w in words)), None)


def parse_balance_table(html: str) -> dict | None:
    """'미상환 전환사채(신주인수권부사채) 발행현황' 구간 HTML → {"total": 원, "base_date": YYYYMMDD|"", "rows": [...]}.

    rows는 회차별 {name, tm, issued, maturity, amount(미상환 권면총액, 원)}. 표가 없고 '해당사항 없음'이면 total 0.
    잔액 열이나 금액 단위를 못 찾으면 None(호출하는 쪽이 추정으로 대신한다).
    머리글 줄은 큰 금액 숫자가 없는 앞쪽 줄이고, 잔액 열은 머리글에 미상환·미전환·잔액이 있고 금액 단어(권면·총액)가 함께 있는 열이다.
    '합계' 행이 있으면 그 값을, 없으면 데이터 행을 더한다.
    """
    soup = BeautifulSoup(html, "html.parser")
    text = soup.get_text(" ", strip=True)
    unit = _unit(text)
    bm = re.search(r"기준일\s*[:：]?\s*(\d{4})\D+(\d{1,2})\D+(\d{1,2})", text)
    base_date = f"{bm.group(1)}{int(bm.group(2)):02d}{int(bm.group(3)):02d}" if bm else ""
    for table in soup.find_all("table"):
        grid = _grid(table)
        n_head = 0
        while n_head < len(grid) and not any((to_int(c) or 0) >= 10_000 for c in grid[n_head]):
            n_head += 1
        if n_head == 0 or n_head >= len(grid) or unit is None:
            continue
        width = max(len(r) for r in grid)
        heads = [" ".join(dict.fromkeys(r[j] for r in grid[:n_head] if j < len(r) and r[j])) for j in range(width)]
        col = next((j for j, h in enumerate(heads) if any(w in h for w in _BAL_WORDS) and any(w in h for w in _AMT_WORDS)
                    and "주식" not in h), None)
        if col is None:
            continue
        c_tm, c_iss, c_mat = _find_col(heads, "회차"), _find_col(heads, "발행일"), _find_col(heads, "만기일")

        def cell(r, j):
            return r[j] if j is not None and j < len(r) else ""

        total, rows = None, []
        for r in grid[n_head:]:
            v = to_int(cell(r, col))
            if v is None:
                continue
            if re.sub(r"\s", "", r[0]).startswith(("합계", "총계", "계")):
                total = v * unit
            elif v > 0:
                rows.append({"name": re.sub(r"\s+", " ", r[0]).strip(), "tm": cell(r, c_tm).strip(),
                             "issued": digits(cell(r, c_iss)), "maturity": digits(cell(r, c_mat)), "amount": v * unit})
        if total is None and rows:
            total = sum(x["amount"] for x in rows)
        if total is not None:
            return {"total": total, "base_date": base_date, "rows": rows}
    if re.search(r"해당\s*사항\s*(이\s*)?없", text) and not soup.find("table"):
        return {"total": 0, "base_date": base_date, "rows": []}
    return None


_CB_HEAD = re.compile(r"미상환\s*전환사채\s*발행현황")
_BW_HEAD = re.compile(r"미상환\s*신주인수권부사채\s*발행현황")
_END_HEAD = re.compile(r"미상환\s*전환형\s*조건부")


def parse_report_node(html: str) -> dict:
    """'증권의 발행을 통한 자금조달에 관한 사항' 노드 HTML → {"CB": parse_balance_table 결과|None, "BW": …}.

    노드 안에서 '미상환 전환사채 발행현황' 소제목부터 '미상환 신주인수권부사채 발행현황' 직전까지가 CB,
    그 뒤부터 '미상환 전환형 조건부자본증권' 직전(없으면 끝)까지가 BW 구간이다. 소제목 뒤 표 제목에도 같은 글이 한 번 더 나오므로
    구간은 첫 소제목 기준으로 자른다.
    """
    cb = _CB_HEAD.search(html)
    bw = _BW_HEAD.search(html, cb.end()) if cb else _BW_HEAD.search(html)
    out = {"CB": None, "BW": None}
    if cb:
        out["CB"] = parse_balance_table(html[cb.start():bw.start() if bw else len(html)])
    if bw:
        end = _END_HEAD.search(html, bw.end())
        out["BW"] = parse_balance_table(html[bw.start():end.start() if end else len(html)])
    return out
