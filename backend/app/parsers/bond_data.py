"""공공데이터포털 채권기본정보(예탁결제원·금융위원회) 응답 가공(순수 함수).

getBondBasiInfo_V2 의 item 은 종목별로 bondBal(채권잔액, 원), bondExprDt(만기일), bondSrfcInrt(표면이율),
scrsItmsKcdNm(종류: 일반회사채·기업어음증권·단기사채 등)을 담는다. 단일 결과는 list 대신 dict 로 오기도 한다.
"""
from __future__ import annotations

from datetime import date

# (key, 제목, scrsItmsKcdNm 에 들어 있는 단어들) — 위에서부터 먼저 맞는 곳에 넣는다.
KINDS: list[tuple[str, str, tuple[str, ...]]] = [
    ("COMMERCIAL_PAPER", "기업어음증권", ("기업어음",)),
    ("SHORT_TERM_BOND", "단기사채", ("단기사채",)),
    ("CONTINGENT_CAPITAL", "조건부자본증권", ("조건부",)),
    ("HYBRID_CAPITAL", "신종자본증권", ("신종자본",)),
    ("CORP_BOND", "회사채", ("회사채",)),
]
OTHER = ("OTHER", "기타 채권")


def as_list(items) -> list[dict]:
    """data.go.kr JSON 은 결과가 1건이면 dict, 0건이면 빈 문자열/리스트로 오기도 한다."""
    if isinstance(items, dict):
        return [items]
    if isinstance(items, list):
        return [i for i in items if isinstance(i, dict)]
    return []


def _int(v) -> int:
    try:
        return int(str(v).replace(",", "").strip() or 0)
    except ValueError:
        return 0


def _fmt_date(d: str) -> str:
    return f"{d[:4]}.{d[4:6]}.{d[6:8]}" if len(d) == 8 and d.isdigit() else d


def _kind(name: str) -> tuple[str, str]:
    for key, title, words in KINDS:
        if any(w in name for w in words):
            return key, title
    return OTHER


def _display_total(won: int) -> str:
    return f"{round(won / 100_000_000):,}억원"


def summarize_outstanding(items, today: date) -> list[dict]:
    """잔액이 남아 있고 만기가 지나지 않은 종목만 종류별로 묶는다. 같은 ISIN 이 여러 일자로 오면 최신 기준일 하나만 쓴다."""
    latest: dict[str, dict] = {}
    for it in as_list(items):
        isin = it.get("isinCd") or it.get("isinCdNm") or ""
        if isin and (isin not in latest or str(it.get("basDt", "")) > str(latest[isin].get("basDt", ""))):
            latest[isin] = it
    today_s = today.strftime("%Y%m%d")
    groups: dict[str, dict] = {}
    for it in latest.values():
        bal = _int(it.get("bondBal"))
        expr = str(it.get("bondExprDt") or "")
        if bal <= 0 or (expr and expr < today_s):
            continue
        key, title = _kind(str(it.get("scrsItmsKcdNm") or ""))
        g = groups.setdefault(key, {"key": key, "title": title, "rows": [], "total": 0})
        parts = [str(it.get("isinCdNm") or it.get("isinCd") or "")]
        if expr:
            parts.append(f"만기 {_fmt_date(expr)}")
        rate = str(it.get("bondSrfcInrt") or "").strip()
        if rate and rate not in ("0", "0.0"):
            parts.append(f"{rate}%")
        g["rows"].append({"label": " · ".join(p for p in parts if p), "is_total": False, "total": bal,
                          "issue_limit": None, "remaining_limit": None, "buckets": [], "_expr": expr})
        g["total"] += bal
    order = [k for k, _, _ in KINDS] + [OTHER[0]]
    out = []
    for key in order:
        g = groups.get(key)
        if not g:
            continue
        g["rows"].sort(key=lambda r: r.pop("_expr") or "99999999")
        g["total_display"] = _display_total(g["total"])
        out.append(g)
    return out
