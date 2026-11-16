"""ETF 일별 스냅샷 집계(순수 함수). 서비스(etf_stats_service)가 SQLite 에서 읽은 행을 넘긴다.

행 형식: (date 'YYYY-MM-DD', code, name, close, nav, net_assets, market_cap, listed_shares)
금액(순자산총액·시가총액)은 원 단위.
"""
from __future__ import annotations

from collections import defaultdict
from typing import Callable, Iterable, Sequence

Row = Sequence  # (date, code, name, close, nav, net_assets, market_cap, listed_shares)

OTHERS = "기타"
UNKNOWN_MANAGER = "운용사 미상"


def _n(v) -> float:
    return float(v) if isinstance(v, (int, float)) else 0.0


def by_date(rows: Iterable[Row]) -> dict[str, list[Row]]:
    out: dict[str, list[Row]] = defaultdict(list)
    for r in rows:
        out[r[0]].append(r)
    return dict(sorted(out.items()))


def overview(rows: Iterable[Row], manager_of: Callable[[str, str], str], top: int = 8) -> dict:
    """전체 합계 시계열 + 운용사별(순자산 상위 top 개 + 기타) 시계열 + 최신일 운용사 표.

    rows 는 날짜 오름차순이어야 한다(SQLite 커서를 그대로 흘려 넣어 메모리를 아낀다).
    manager_of(code, name) → 운용사명(모르면 ''). 같은 코드는 한 번만 묻는다.
    """
    dates: list[str] = []
    per: dict[str, dict[str, list[float]]] = {}  # manager -> {'count','net_assets','market_cap'} 리스트(날짜 순)
    totals: dict[str, list[float]] = {"count": [], "net_assets": [], "market_cap": []}
    mgr_of_code: dict[str, str] = {}
    i = -1
    for r in rows:
        if not dates or dates[-1] != r[0]:
            dates.append(r[0])
            i += 1
            for v in totals.values():
                v.append(0.0)
            for s in per.values():
                for v in s.values():
                    v.append(0.0)
        m = mgr_of_code.get(r[1])
        if m is None:
            m = mgr_of_code[r[1]] = manager_of(r[1], r[2] or "") or UNKNOWN_MANAGER
        s = per.get(m)
        if s is None:
            s = per[m] = {"count": [0.0] * len(dates), "net_assets": [0.0] * len(dates), "market_cap": [0.0] * len(dates)}
        na, mc = _n(r[5]), _n(r[6])
        s["count"][i] += 1
        s["net_assets"][i] += na
        s["market_cap"][i] += mc
        totals["count"][i] += 1
        totals["net_assets"][i] += na
        totals["market_cap"][i] += mc
    if not dates:
        return {"dates": [], "total": {"count": [], "net_assets": [], "market_cap": []}, "managers": [], "manager_table": []}
    totals = {"count": [int(v) for v in totals["count"]], "net_assets": [round(v) for v in totals["net_assets"]], "market_cap": [round(v) for v in totals["market_cap"]]}

    last = len(dates) - 1
    total_na = totals["net_assets"][last] or 0
    table = []
    for m, s in per.items():
        first_idx = next((i for i in range(len(dates)) if s["count"][i] > 0), last)
        table.append({
            "name": m,
            "count": int(s["count"][last]),
            "net_assets": round(s["net_assets"][last]),
            "market_cap": round(s["market_cap"][last]),
            "share": round(s["net_assets"][last] / total_na * 100, 2) if total_na else None,
            # 기간 첫날 대비 변화(그 운용사가 기간 중간에 처음 나타났으면 0 에서 출발한 것으로 본다)
            "count_change": int(s["count"][last] - (s["count"][0] if first_idx == 0 else 0)),
            "net_assets_change": round(s["net_assets"][last] - (s["net_assets"][0] if first_idx == 0 else 0)),
        })
    table.sort(key=lambda t: (-t["net_assets"], -t["count"], t["name"]))
    table = [t for t in table if t["count"] > 0 or t["net_assets_change"] != 0]

    head = [t["name"] for t in table if t["count"] > 0][:top]
    series = []
    for m in head:
        s = per[m]
        series.append({"name": m, "count": [int(v) for v in s["count"]], "net_assets": [round(v) for v in s["net_assets"]], "market_cap": [round(v) for v in s["market_cap"]]})
    rest = [m for m in per if m not in head]
    if rest:
        series.append({
            "name": OTHERS,
            "count": [int(sum(per[m]["count"][i] for m in rest)) for i in range(len(dates))],
            "net_assets": [round(sum(per[m]["net_assets"][i] for m in rest)) for i in range(len(dates))],
            "market_cap": [round(sum(per[m]["market_cap"][i] for m in rest)) for i in range(len(dates))],
        })
    return {"dates": dates, "total": totals, "managers": series, "manager_table": table}


SORTS = ("net_assets", "market_cap", "net_assets_change", "net_assets_change_pct", "market_cap_change")


def ranking(latest: Iterable[Row], base: Iterable[Row], manager_of: Callable[[str, str], str], sort: str = "net_assets", manager: str = "") -> list[dict]:
    """최신일 순위. base 는 비교 기준일(기간 첫날)의 행. 기준일에 없던 ETF(신규 상장)는 변화량을 None 으로 둔다."""
    if sort not in SORTS:
        sort = "net_assets"
    prev = {r[1]: r for r in base}
    has_base = bool(prev)
    items = []
    for r in latest:
        m = manager_of(r[1], r[2] or "")
        if manager and m != manager:
            continue
        p = prev.get(r[1])
        na, mc = r[5], r[6]
        pna, pmc = (p[5], p[6]) if p else (None, None)
        na_chg = (na - pna) if na is not None and pna is not None else None
        items.append({
            "code": r[1],
            "name": r[2] or r[1],
            "manager": m,
            "close": r[3],
            "nav": r[4],
            "net_assets": na,
            "market_cap": mc,
            "listed_shares": r[7],
            "net_assets_change": na_chg,
            "net_assets_change_pct": round(na_chg / pna * 100, 2) if na_chg is not None and pna else None,
            "market_cap_change": (mc - pmc) if mc is not None and pmc is not None else None,
            "new": has_base and p is None,
        })
    # None 은 맨 뒤로
    items.sort(key=lambda it: (it[sort] is None, -(it[sort] or 0), it["code"]))
    for i, it in enumerate(items, 1):
        it["rank"] = i
    return items


def series_of(rows: Iterable[Row]) -> dict:
    """한 ETF 의 날짜 순 시계열."""
    rows = sorted(rows, key=lambda r: r[0])
    return {
        "dates": [r[0] for r in rows],
        "close": [r[3] for r in rows],
        "nav": [r[4] for r in rows],
        "net_assets": [r[5] for r in rows],
        "market_cap": [r[6] for r in rows],
        "listed_shares": [r[7] for r in rows],
    }


def change(values: list, back: int) -> dict | None:
    """마지막 값과 back 개 앞(없으면 첫 값) 비교. 값이 없으면 None."""
    vals = [v for v in values if v is not None]
    if len(vals) < 2:
        return None
    cur, prev = vals[-1], vals[max(0, len(vals) - 1 - back)]
    return {"from": prev, "to": cur, "diff": cur - prev, "pct": round((cur - prev) / prev * 100, 2) if prev else None}


def parse_naver_etf_list(data: dict) -> dict[str, dict]:
    """네이버 증권 ETF 전종목 시세(finance.naver.com/api/sise/etfItemList.nhn) → {코드: 일별 자료}.

    네이버는 순자산총액·상장좌수를 주지 않아 시가총액(marketSum, 억원)으로 추정한다:
      상장좌수 ≈ 시가총액 / 현재가, 순자산총액 ≈ 시가총액 × NAV / 현재가.
    """
    res = data.get("result") if isinstance(data, dict) else None
    rows = (res or {}).get("etfItemList") if isinstance(res, dict) else None
    out: dict[str, dict] = {}
    for r in rows or []:
        if not isinstance(r, dict):
            continue
        code = str(r.get("itemcode") or "").strip()
        if len(code) != 6:
            continue
        try:
            price = float(r.get("nowVal") or 0)
            nav = float(r.get("nav") or 0)
            msum = float(r.get("marketSum") or 0) * 1e8
        except (TypeError, ValueError):
            continue
        if price <= 0 or msum <= 0:
            continue
        out[code] = {
            "code": code,
            "name": str(r.get("itemname") or "").strip(),
            "close": int(round(price)),
            "nav": nav or None,
            "market_cap": int(round(msum)),
            "listed_shares": int(round(msum / price)),
            "net_assets": int(round(msum * (nav / price if nav > 0 else 1.0))),
        }
    return out
