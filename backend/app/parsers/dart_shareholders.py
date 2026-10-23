"""최대주주 현황 탭: DART 최대주주 현황(hyslrSttus)·최대주주 변동현황(hyslrChgSttus) 응답 가공(순수 함수)."""
from __future__ import annotations

from ..utils.money import parse_amount


def _txt(v) -> str:
    s = (v or "").strip() if isinstance(v, str) else ""
    return "" if s in ("-", "") else s


def _rate(v) -> float | None:
    s = _txt(v).replace(",", "").replace("%", "")
    try:
        return float(s)
    except ValueError:
        return None


def _is_common_kind(kind: str) -> bool:
    k = kind.replace(" ", "")
    return k == "" or "보통" in k or "의결권있는" in k


def summarize_holders(rows: list[dict]) -> dict:
    """최대주주 및 특수관계인 표. '계' 행은 합계로 따로 뺀다."""
    holders: list[dict] = []
    totals: list[dict] = []
    for r in rows:
        name = _txt(r.get("nm"))
        item = {
            "name": name,
            "relation": _txt(r.get("relate")),
            "stock_kind": _txt(r.get("stock_knd")),
            "begin_shares": parse_amount(r.get("bsis_posesn_stock_co")),
            "begin_ratio": _rate(r.get("bsis_posesn_stock_qota_rt")),
            "end_shares": parse_amount(r.get("trmend_posesn_stock_co")),
            "end_ratio": _rate(r.get("trmend_posesn_stock_qota_rt")),
            "note": _txt(r.get("rm")),
        }
        if name in ("계", "합계"):
            totals.append(item)
        elif name:
            holders.append(item)
    # 보통주·우선주 등 주식 종류별로 행이 따로 오므로 합계도 종류별로 둔다.
    # total은 보통주(없으면 첫 합계) 하나만 가리키는 하위호환 필드다.
    total = next((t for t in totals if _is_common_kind(t["stock_kind"])), totals[0] if totals else None)
    commons = [h for h in holders if h["end_ratio"] is not None and _is_common_kind(h["stock_kind"])]
    largest = max(commons or [h for h in holders if h["end_ratio"] is not None], key=lambda h: h["end_ratio"], default=None)
    return {"holders": holders, "total": total, "totals": totals, "largest": largest["name"] if largest else None}


def summarize_changes(rows: list[dict]) -> list[dict]:
    out = []
    for r in rows:
        name = _txt(r.get("mxmm_shrholdr_nm"))
        if not name:
            continue
        out.append({
            "date": _txt(r.get("change_on")),
            "name": name,
            "shares": parse_amount(r.get("posesn_stock_co")),
            "ratio": _rate(r.get("qota_rt")),
            "cause": _txt(r.get("change_cause")),
            "note": _txt(r.get("rm")),
        })
    return out
