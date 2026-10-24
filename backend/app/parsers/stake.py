"""지분 변동 공시 파싱: 주식등의 대량보유상황보고(majorstock) · 임원ㆍ주요주주 특정증권등 소유상황보고(elestock).

DART 응답은 숫자도 문자열("1,200", "-")로 오고 필드가 비는 경우가 많아 모두 방어적으로 읽는다.
필드명은 DART 개발가이드(DS004) 기준이다.
"""
from __future__ import annotations


def _int(v: object) -> int | None:
    t = str(v or "").replace(",", "").replace("+", "").strip()
    if not t or t == "-":
        return None
    try:
        return int(float(t))
    except ValueError:
        return None


def _float(v: object) -> float | None:
    t = str(v or "").replace(",", "").replace("%", "").replace("+", "").strip()
    if not t or t == "-":
        return None
    try:
        return float(t)
    except ValueError:
        return None


def _date(v: object) -> str:
    return str(v or "").replace("-", "").strip()


def parse_major(rows: list[dict]) -> list[dict]:
    """5% 대량보유 보고 → 최신 접수순."""
    out = [
        {
            "rcept_no": r.get("rcept_no", ""),
            "date": _date(r.get("rcept_dt")),
            "reporter": (r.get("repror") or "").strip(),
            "report_type": (r.get("report_tp") or "").strip(),
            "reason": (r.get("report_resn") or "").strip(),
            "shares": _int(r.get("stkqy")),
            "shares_change": _int(r.get("stkqy_irds")),
            "ratio": _float(r.get("stkrt")),
            "ratio_change": _float(r.get("stkrt_irds")),
        }
        for r in rows
    ]
    return sorted(out, key=lambda x: (x["date"], x["rcept_no"]), reverse=True)


def parse_insider(rows: list[dict]) -> list[dict]:
    """임원·주요주주 소유 보고 → 최신 접수순. shares_change 가 양수면 매수·취득, 음수면 매도·처분."""
    out = [
        {
            "rcept_no": r.get("rcept_no", ""),
            "date": _date(r.get("rcept_dt")),
            "reporter": (r.get("repror") or "").strip(),
            "position": (r.get("isu_exctv_ofcps") or "").strip(),
            "main_holder": (r.get("isu_main_shrholdr") or "").strip(),
            "shares": _int(r.get("sp_stock_lmp_cnt")),
            "shares_change": _int(r.get("sp_stock_lmp_irds_cnt")),
            "ratio": _float(r.get("sp_stock_lmp_rate")),
            "ratio_change": _float(r.get("sp_stock_lmp_irds_rate")),
        }
        for r in rows
    ]
    return sorted(out, key=lambda x: (x["date"], x["rcept_no"]), reverse=True)
