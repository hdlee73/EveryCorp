"""키움 REST API 시장 데이터 파서(순수 함수). 계좌·주문 응답은 다루지 않는다.

필드명 근거: 2026-11-12 실서버(app.diag --market)로 ka10080·82·83·04·59·90013·14·13·랭킹 응답 키를 확인했다.
키움은 예고 없이 바꿀 수 있으니 이상하면 diag 로 다시 확인한다.
- 차트 ka10079~ka10083: 목록 키 stk_tic_chart_qry / stk_min_pole_chart_qry / stk_dt_pole_chart_qry /
  stk_stk_pole_chart_qry / stk_mth_pole_chart_qry, 행 dt|cntr_tm, cur_prc, open_pric, high_pric, low_pric, trde_qty.
  분봉 가격에는 방향 부호(+/-)가 붙어 오므로 절댓값을 쓴다.
- 호가 ka10004: sel_fpr_bid/sel_fpr_req(1호가), sel_{n}th_pre_bid/sel_{n}th_pre_req(2~10호가), buy_* 동일, tot_sel_req, tot_buy_req, bid_req_base_tm.
- 투자자별 ka10059: stk_invsr_orgn[] dt, cur_prc, ind_invsr, frgnr_invsr, orgn, fnnc_invt, insrnc, invtrt, etc_fnnc, bank, penfnd_etc, samo_fund, natn, etc_corp.
- 프로그램 ka90013: stk_daly_prm_trde_trnsn[] dt, prm_sell_amt, prm_buy_amt, prm_netprps_amt(백만원), prm_netprps_qty.
- 공매도 ka10014: shrts_trnsn[] dt, close_pric, trde_qty, shrts_qty, ovr_shrts_qty, trde_wght, shrts_trde_prica, shrts_avg_pric.
- 신용 ka10013: crd_trde_trend[] dt, new, rpya, remn, amt, pre, shr_rt, remn_rt.
- 랭킹 ka10030 tdy_trde_qty_upper[] / ka10027 pred_pre_flu_rt_upper[]: stk_cd, stk_nm, cur_prc, pred_pre, flu_rt, trde_qty|now_trde_qty, trde_amt.
- 실시간 0B 주식체결 FID 10 현재가, 11 전일대비, 12 등락율, 13 누적거래량, 14 누적거래대금(백만원), 15 거래량(+매수/-매도), 20 체결시간, 228 체결강도.
  0D 주식호가잔량 FID 41~50 매도호가, 51~60 매수호가, 61~70 매도잔량, 71~80 매수잔량, 121 매도총잔량, 125 매수총잔량, 21 호가시간.
"""
from __future__ import annotations

import re

_NUM = re.compile(r"[^\d.\-+]")


def num(v, absolute: bool = False) -> float | None:
    """'+1,234' '-0.5' '' → 숫자. 빈 값·해석 불가면 None."""
    if v is None:
        return None
    if isinstance(v, (int, float)):
        x = float(v)
    else:
        s = _NUM.sub("", str(v).strip())
        # 키움은 음수 증감에 '--110946'처럼 부호를 겹쳐 보내기도 한다(ka90013 *_irds). 앞쪽 부호를 하나로 줄인다.
        m = re.match(r"^([+\-]+)(.*)$", s)
        if m:
            s = ("-" if "-" in m.group(1) else "") + m.group(2)
        if s in ("", "+", "-", ".", "+.", "-."):
            return None
        try:
            x = float(s)
        except ValueError:
            return None
    return abs(x) if absolute else x


def inum(v, absolute: bool = False) -> int | None:
    x = num(v, absolute)
    return int(round(x)) if x is not None else None


def _date(s) -> str | None:
    s = str(s or "").strip()
    if len(s) >= 8 and s[:8].isdigit():
        return f"{s[:4]}-{s[4:6]}-{s[6:8]}"
    return None


def _direction(change: float | None) -> str:
    if not change:
        return "FLAT"
    return "UP" if change > 0 else "DOWN"


# ───────────────────────── 차트(캔들) ─────────────────────────

CHART_LIST_KEYS = {
    "ka10079": "stk_tic_chart_qry",
    "ka10080": "stk_min_pole_chart_qry",
    "ka10081": "stk_dt_pole_chart_qry",
    "ka10082": "stk_stk_pole_chart_qry",
    "ka10083": "stk_mth_pole_chart_qry",
}


def parse_candles(api_id: str, data: dict) -> list[dict]:
    """차트 응답 → [{t, o, h, l, c, v}] 오름차순. t 는 일봉 이상 'YYYY-MM-DD', 분봉 'YYYY-MM-DDTHH:MM'."""
    rows = data.get(CHART_LIST_KEYS.get(api_id, ""), None)
    if rows is None:  # 키가 바뀐 경우 대비: *_chart_qry 로 끝나는 첫 목록
        rows = next((v for k, v in data.items() if k.endswith("chart_qry") and isinstance(v, list)), [])
    out: dict[str, dict] = {}
    intraday = api_id in ("ka10079", "ka10080")
    for r in rows or []:
        if not isinstance(r, dict):
            continue
        c = inum(r.get("cur_prc"), absolute=True)
        if not c:
            continue
        if intraday:
            tm = str(r.get("cntr_tm", ""))
            if len(tm) < 12 or not tm[:12].isdigit():
                continue
            t = f"{tm[:4]}-{tm[4:6]}-{tm[6:8]}T{tm[8:10]}:{tm[10:12]}"
        else:
            t = _date(r.get("dt"))
            if not t:
                continue
        o = inum(r.get("open_pric"), absolute=True) or c
        h = inum(r.get("high_pric"), absolute=True) or max(o, c)
        lo = inum(r.get("low_pric"), absolute=True) or min(o, c)
        out[t] = {"t": t, "o": o, "h": max(h, o, c), "l": min(lo, o, c), "c": c,
                  "v": inum(r.get("trde_qty"), absolute=True) or 0}
    return sorted(out.values(), key=lambda x: x["t"])


def aggregate_candles(daily: list[dict], period: str) -> list[dict]:
    """일봉({date, open?, high, low, close, volume}) → 주봉(W)/월봉(M). 키움이 없을 때 폴백용.
    주 단위는 ISO 주(월~금), 월 단위는 YYYY-MM. t 는 그 구간 첫 거래일."""
    from datetime import date as _d

    buckets: dict[str, dict] = {}
    for p in daily:
        ds = p.get("date") or p.get("t")
        c = p.get("close") if "close" in p else p.get("c")
        if not ds or not c:
            continue
        y, m, d = int(ds[:4]), int(ds[5:7]), int(ds[8:10])
        if period == "W":
            iso = _d(y, m, d).isocalendar()
            key = f"{iso[0]}-W{iso[1]:02d}"
        else:
            key = ds[:7]
        o = p.get("open") or p.get("o") or c
        h = p.get("high") or p.get("h") or c
        lo = p.get("low") or p.get("l") or c
        v = p.get("volume") or p.get("v") or 0
        b = buckets.get(key)
        if b is None:
            buckets[key] = {"t": ds[:10], "o": o, "h": h, "l": lo, "c": c, "v": v}
        else:
            b["h"] = max(b["h"], h)
            b["l"] = min(b["l"], lo)
            b["c"] = c
            b["v"] += v
    return sorted(buckets.values(), key=lambda x: x["t"])


# ───────────────────────── 호가 ─────────────────────────

def parse_orderbook(data: dict) -> dict:
    """ka10004 → {asks:[{price,qty}] 1호가부터, bids:[...], total_ask, total_bid, time}."""
    asks, bids = [], []
    for n in range(1, 11):
        if n == 1:  # 1호가 키는 sel_fpr_* 이고, 일부 응답은 sel_1th_pre_* 로 온다
            sp, sq, bp, bq = "sel_fpr_bid", "sel_fpr_req", "buy_fpr_bid", "buy_fpr_req"
            if data.get(sp) in (None, ""):
                sp, sq = "sel_1th_pre_bid", "sel_1th_pre_req"
            if data.get(bp) in (None, ""):
                bp, bq = "buy_1th_pre_bid", "buy_1th_pre_req"
        else:
            sp, sq = f"sel_{n}th_pre_bid", f"sel_{n}th_pre_req"
            bp, bq = f"buy_{n}th_pre_bid", f"buy_{n}th_pre_req"
        a, b = inum(data.get(sp), True), inum(data.get(bp), True)
        if a:
            asks.append({"price": a, "qty": inum(data.get(sq), True) or 0})
        if b:
            bids.append({"price": b, "qty": inum(data.get(bq), True) or 0})
    tm = str(data.get("bid_req_base_tm") or "")
    return {
        "asks": asks,
        "bids": bids,
        # 총잔량 키가 없으면 받은 호가 잔량 합으로 대신한다
        "total_ask": inum(data.get("tot_sel_req"), True) if data.get("tot_sel_req") not in (None, "") else (sum(x["qty"] for x in asks) if asks else None),
        "total_bid": inum(data.get("tot_buy_req"), True) if data.get("tot_buy_req") not in (None, "") else (sum(x["qty"] for x in bids) if bids else None),
        "time": f"{tm[:2]}:{tm[2:4]}:{tm[4:6]}" if len(tm) >= 6 and tm[:6].isdigit() else None,
    }


# ───────────────────────── 수급 ─────────────────────────

INVESTOR_FIELDS = {
    "individual": "ind_invsr",
    "foreign": "frgnr_invsr",
    "institution": "orgn",
    "financial_invest": "fnnc_invt",
    "insurance": "insrnc",
    "trust": "invtrt",
    "etc_finance": "etc_fnnc",
    "bank": "bank",
    "pension": "penfnd_etc",
    "private_fund": "samo_fund",
    "nation": "natn",
    "etc_corp": "etc_corp",
}


def _rate_x100(v) -> float | None:
    """ka10059 의 flu_rt 는 소수점 없이 100배 값으로 온다('+97' = 0.97%). 소수점이 있으면 그대로 쓴다."""
    x = num(v)
    if x is None or "." in str(v):
        return x
    return x / 100


def parse_investors(data: dict) -> list[dict]:
    """ka10059 순매수(수량, 단주) → [{date, close, change_rate, individual, foreign, institution, ...}] 최신순."""
    out = []
    for r in data.get("stk_invsr_orgn") or []:
        d = _date(r.get("dt"))
        if not d:
            continue
        row = {"date": d, "close": inum(r.get("cur_prc"), True), "change_rate": _rate_x100(r.get("flu_rt")),
               "volume": inum(r.get("acc_trde_qty"), True)}
        for k, f in INVESTOR_FIELDS.items():
            row[k] = inum(r.get(f))
        out.append(row)
    return sorted(out, key=lambda x: x["date"], reverse=True)


def parse_program(data: dict) -> list[dict]:
    """ka90013 → [{date, sell_amt, buy_amt, net_amt(백만원), net_qty}] 최신순."""
    out = []
    for r in data.get("stk_daly_prm_trde_trnsn") or []:
        d = _date(r.get("dt"))
        if not d:
            continue
        out.append({"date": d, "close": inum(r.get("cur_prc"), True),
                    "sell_amt": inum(r.get("prm_sell_amt"), True), "buy_amt": inum(r.get("prm_buy_amt"), True),
                    "net_amt": inum(r.get("prm_netprps_amt")), "net_qty": inum(r.get("prm_netprps_qty"))})
    return sorted(out, key=lambda x: x["date"], reverse=True)


def parse_short(data: dict) -> list[dict]:
    """ka10014 → [{date, close, volume, short_qty, short_ratio(%), short_amt(천원), short_avg_price}] 최신순.
    실서버 확인(2026-11-12): shrts_trde_prica 113031339 = 564,013주 × 200,406원 ÷ 1000 → 천원 단위."""
    out = []
    for r in data.get("shrts_trnsn") or []:
        d = _date(r.get("dt"))
        if not d:
            continue
        out.append({"date": d, "close": inum(r.get("close_pric"), True), "volume": inum(r.get("trde_qty"), True),
                    "short_qty": inum(r.get("shrts_qty"), True), "short_cum_qty": inum(r.get("ovr_shrts_qty"), True),
                    "short_ratio": num(r.get("trde_wght"), True), "short_amt": inum(r.get("shrts_trde_prica"), True),
                    "short_avg_price": inum(r.get("shrts_avg_pric"), True)})
    return sorted(out, key=lambda x: x["date"], reverse=True)


def parse_credit(data: dict) -> list[dict]:
    """ka10013(융자) → [{date, new, repay, balance(주), amount(백만원), balance_rate(%), supply_rate(%)}] 최신순."""
    out = []
    for r in data.get("crd_trde_trend") or []:
        d = _date(r.get("dt"))
        if not d:
            continue
        out.append({"date": d, "close": inum(r.get("cur_prc"), True), "new": inum(r.get("new"), True),
                    "repay": inum(r.get("rpya"), True), "balance": inum(r.get("remn"), True),
                    "amount": inum(r.get("amt"), True), "change": inum(r.get("pre")),
                    "supply_rate": num(r.get("shr_rt"), True), "balance_rate": num(r.get("remn_rt"), True)})
    return sorted(out, key=lambda x: x["date"], reverse=True)


# ───────────────────────── 랭킹 ─────────────────────────

def parse_ranking(data: dict) -> list[dict]:
    rows = data.get("tdy_trde_qty_upper")
    if rows is None:
        rows = data.get("pred_pre_flu_rt_upper")
    if rows is None:
        rows = next((v for k, v in data.items() if k.endswith("_upper") and isinstance(v, list)), [])
    out = []
    for r in rows or []:
        code = re.sub(r"[^0-9A-Z]", "", str(r.get("stk_cd", "")).split("_")[0].upper())
        if len(code) != 6:
            continue
        change = inum(r.get("pred_pre"))
        out.append({
            "stock_code": code,
            "name": str(r.get("stk_nm", "")).strip(),
            "price": inum(r.get("cur_prc"), True),
            "change": change,
            "change_rate": num(r.get("flu_rt")),
            "direction": _direction(change if change is not None else num(r.get("flu_rt"))),
            "volume": inum(r.get("trde_qty") if r.get("trde_qty") not in (None, "") else r.get("now_trde_qty"), True),
            "trade_value": inum(r.get("trde_amt"), True),  # 백만원(ka10030)
        })
    return out


# ───────────────────────── 실시간(WebSocket) ─────────────────────────

def parse_real_trade(code: str, v: dict) -> dict:
    change = inum(v.get("11"))
    tm = str(v.get("20") or "")
    return {
        "type": "trade", "code": code,
        "price": inum(v.get("10"), True), "change": change, "change_rate": num(v.get("12")),
        "direction": _direction(change),
        "volume": inum(v.get("15")),  # +매수 / -매도 체결
        "acc_volume": inum(v.get("13"), True),
        "acc_value": inum(v.get("14"), True),  # 백만원
        "open": inum(v.get("16"), True), "high": inum(v.get("17"), True), "low": inum(v.get("18"), True),
        "strength": num(v.get("228"), True),
        "time": f"{tm[:2]}:{tm[2:4]}:{tm[4:6]}" if len(tm) >= 6 else None,
    }


def parse_real_orderbook(code: str, v: dict) -> dict:
    asks, bids = [], []
    for i in range(10):
        a, b = inum(v.get(str(41 + i)), True), inum(v.get(str(51 + i)), True)
        if a:
            asks.append({"price": a, "qty": inum(v.get(str(61 + i)), True) or 0})
        if b:
            bids.append({"price": b, "qty": inum(v.get(str(71 + i)), True) or 0})
    tm = str(v.get("21") or "")
    return {"type": "orderbook", "code": code, "asks": asks, "bids": bids,
            "total_ask": inum(v.get("121"), True), "total_bid": inum(v.get("125"), True),
            "time": f"{tm[:2]}:{tm[2:4]}:{tm[4:6]}" if len(tm) >= 6 else None}


def parse_real_message(msg: dict) -> list[dict]:
    """키움 REAL 메시지 → 앱으로 보낼 정규화 이벤트 목록. 시세(0B)·호가(0D) 외 유형(주문체결·잔고 등)은 버린다."""
    if msg.get("trnm") != "REAL":
        return []
    out = []
    for d in msg.get("data") or []:
        if not isinstance(d, dict):
            continue
        code = str(d.get("item", "")).split("_")[0]
        values = d.get("values") or {}
        t = d.get("type")
        if t == "0B":
            out.append(parse_real_trade(code, values))
        elif t == "0D":
            out.append(parse_real_orderbook(code, values))
    return out
