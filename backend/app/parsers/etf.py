"""키움 ETF TR 응답 파서(순수 함수).

- ka40004(ETF 전체시세) etfall_mrpr: 목록 검색용
- ka40002(ETF 종목정보): 기초지수·과세유형·액면가
- ka40003(ETF 일별추이) etfdaly_trnsn: 종가·NAV·괴리율·추적오차
금액·비율은 '+87045', '-0.19' 처럼 부호가 붙은 문자열이라 부호를 떼거나 실수로 바꿔 쓴다(2026-10 실서버 응답 기준).
"""
from __future__ import annotations

from .price import kiwoom_abs_int, kiwoom_float


def _nav(raw) -> float | None:
    """NAV는 가격이라 음수일 수 없다. 키움이 전일대비 방향 부호(-66160)를 붙여 보내는 날이 있어 절댓값으로 쓴다."""
    v = kiwoom_float(raw)
    return None if v is None else abs(v)


def _txt(v) -> str:
    s = (v or "").strip() if isinstance(v, str) else ""
    return "" if s == "-" else s


def parse_etf_list(rows: list[dict]) -> list[dict]:
    out = []
    for r in rows:
        code = _txt(r.get("stk_cd"))
        name = _txt(r.get("stk_nm"))
        if not code or not name:
            continue
        out.append({
            "code": code,
            "name": name,
            "index_name": _txt(r.get("trace_idex_nm")),
            "price": kiwoom_abs_int(r.get("close_pric")),
            "change_rate": kiwoom_float(r.get("pre_rt")),
            "nav": _nav(r.get("nav")),
            "volume": kiwoom_abs_int(r.get("trde_qty")),
        })
    return out


def search_etfs(items: list[dict], q: str, limit: int = 20) -> list[dict]:
    """종목명(공백·대소문자 무시) 부분일치 또는 코드 앞부분 일치. 이름이 q로 시작하는 것을 먼저."""
    key = "".join(q.split()).lower()
    if not key:
        return []
    starts, contains = [], []
    for it in items:
        name = "".join(it["name"].split()).lower()
        if it["code"].lower().startswith(key) or name.startswith(key):
            starts.append(it)
        elif key in name or key in "".join(it.get("index_name", "").split()).lower():
            contains.append(it)
    return (starts + contains)[:limit]


def parse_etf_info(d: dict) -> dict:
    return {
        "name": _txt(d.get("stk_nm")),
        "index_name": _txt(d.get("etfobjt_idex_nm")),
        "par_value": kiwoom_abs_int(d.get("wonju_pric")),
        "tax_type": _txt(d.get("etftxon_type")),
    }


def _tracking_error(raw) -> float | None:
    """ka40003의 추적오차율은 소수점 없이(69 = 0.69%) 오는 것이 확인돼 100으로 나눈다. 소수점이 있으면 그대로 쓴다."""
    s = _txt(raw if isinstance(raw, str) else str(raw or ""))
    v = kiwoom_float(s)
    if v is None:
        return None
    return round(v if "." in s else v / 100, 2)


def parse_etf_daily(rows: list[dict]) -> list[dict]:
    """ka40003 etfdaly_trnsn → 최신순 [{date, close, change_rate, volume, nav, disparity, tracking_error}]."""
    out = []
    for r in rows:
        d = _txt(r.get("cntr_dt"))
        if len(d) != 8:
            continue
        out.append({
            "date": f"{d[:4]}-{d[4:6]}-{d[6:]}",
            "close": kiwoom_abs_int(r.get("cur_prc")),
            "change_rate": kiwoom_float(r.get("pre_rt")),
            "volume": kiwoom_abs_int(r.get("trde_qty")),
            "nav": _nav(r.get("nav")),
            "disparity": kiwoom_float(r.get("navetfdispty_rt")),
            "tracking_error": _tracking_error(r.get("trace_eor_rt")),
        })
    return sorted(out, key=lambda x: x["date"], reverse=True)
