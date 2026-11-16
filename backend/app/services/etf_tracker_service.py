"""ETF Tracker 앱 전용 확장 서비스: 실시간 iNAV·LP 괴리율, KOFIA 실부담비용률, 액티브 리밸런싱 감지."""
from __future__ import annotations

import json
import logging
import os
import tempfile
from pathlib import Path

from . import etf_portfolio_service, etf_service, kiwoom_client
from .etf_service import check_code

log = logging.getLogger(__name__)


def _snapshot_dir() -> Path:
    base = Path(os.getenv("ETF_SNAPSHOT_DIR", "/srv/data/etf_pdf_snapshots"))
    try:
        base.mkdir(parents=True, exist_ok=True)
        return base
    except OSError:
        fallback = Path(tempfile.gettempdir()) / "etf_pdf_snapshots"
        fallback.mkdir(parents=True, exist_ok=True)
        return fallback


def _to_int(val: object, default: int = 0) -> int:
    try:
        return abs(int(float(str(val).replace(",", "").strip())))
    except (TypeError, ValueError):
        return default


async def get_live_metrics(code: str) -> dict:
    """키움 현재가·iNAV·호가 잔량을 결합해 실시간 괴리율 및 LP 호가 공백 경보를 반환한다."""
    code = check_code(code)
    etf = await etf_service.get_etf(code)
    quote = etf.get("quote") or {}
    daily = etf.get("daily") or []
    basics = etf.get("basics") or {}

    market_price = _to_int(quote.get("price"), 0)
    i_nav = float(daily[0].get("nav") or 0.0) if daily else float(basics.get("nav_krx") or 0.0)
    if i_nav <= 0.0 and basics.get("nav_krx"):
        i_nav = float(basics["nav_krx"])

    discrepancy_pct = ((market_price - i_nav) / i_nav * 100.0) if (i_nav > 0.0 and market_price > 0) else 0.0

    lp_ask_qty = 0
    lp_bid_qty = 0
    try:
        ob = await kiwoom_client.orderbook(code)
        lp_ask_qty = _to_int(ob.get("tot_sel_req") or ob.get("sel_1bid_req"), 0)
        lp_bid_qty = _to_int(ob.get("tot_buy_req") or ob.get("buy_1bid_req"), 0)
    except Exception as exc:  # noqa: BLE001
        log.info("Orderbook fallback for %s: %s", code, exc)

    abs_diff = abs(discrepancy_pct)
    lp_weak = (lp_ask_qty > 0 or lp_bid_qty > 0) and min(lp_ask_qty, lp_bid_qty) < 500
    if abs_diff >= 0.8 or (abs_diff >= 0.4 and lp_weak):
        warning_level = "OVERHEAT_ALERT"
    elif abs_diff >= 0.3 or lp_weak:
        warning_level = "CAUTION"
    else:
        warning_level = "NORMAL"

    return {
        "code": code,
        "name": etf.get("name", code),
        "market_price": market_price,
        "i_nav": round(i_nav, 2),
        "discrepancy_pct": round(discrepancy_pct, 3),
        "lp_ask_qty": lp_ask_qty,
        "lp_bid_qty": lp_bid_qty,
        "warning_level": warning_level,
        "tracking_error_pct": float(daily[0].get("tracking_error") or 0.0) if daily else 0.0,
    }


async def get_real_costs(code: str, investment_won: int = 10_000_000) -> dict:
    """총보수 + 기타비용 + 매매·중개수수료율 기반 실부담비용률 및 1원 단위 연간 비용을 계산한다."""
    code = check_code(code)
    etf = await etf_service.get_etf(code)
    basics = etf.get("basics") or {}
    ter_pct = float(basics.get("total_fee") or 0.15)
    asset_class = str(basics.get("asset_class") or "")

    # 해외/액티브 여부에 따른 KOFIA 평균 기타·매매중개수수료율 보정
    is_foreign = "해외" in asset_class or "미국" in str(etf.get("name", ""))
    other_cost_pct = 0.04 if is_foreign else 0.02
    brokerage_fee_pct = 0.05 if "액티브" in str(etf.get("name", "")) else 0.03
    real_cost_pct = round(ter_pct + other_cost_pct + brokerage_fee_pct, 4)
    annual_cost_won = round(max(investment_won, 0) * (real_cost_pct / 100.0))

    return {
        "code": code,
        "name": etf.get("name", code),
        "ter_pct": round(ter_pct, 4),
        "other_cost_pct": other_cost_pct,
        "brokerage_fee_pct": brokerage_fee_pct,
        "real_cost_pct": real_cost_pct,
        "investment_won": max(investment_won, 0),
        "annual_cost_won": annual_cost_won,
    }


async def get_rebalancing(code: str) -> dict:
    """전일 저장된 PDF 스냅샷과 금일 PDF를 비교해 편입·편출 및 수량 증감을 감지한다."""
    code = check_code(code)
    portfolio = await etf_portfolio_service.get_portfolio(code)
    cur_date = str(portfolio.get("date") or "")
    holdings = portfolio.get("holdings") or []

    cur_map = {
        str(h.get("code") or h.get("name")): {
            "code": str(h.get("code") or ""),
            "name": str(h.get("name") or h.get("corp_name") or ""),
            "qty": float(h.get("qty") or 0.0),
            "weight": float(h.get("weight") or 0.0),
        }
        for h in holdings
        if (h.get("code") or h.get("name"))
    }

    snap_file = _snapshot_dir() / f"{code}.json"
    prev_snapshot: dict = {}
    if snap_file.exists():
        try:
            prev_snapshot = json.loads(snap_file.read_text(encoding="utf-8"))
        except Exception:  # noqa: BLE001
            prev_snapshot = {}

    prev_date = str(prev_snapshot.get("prev_date") or "")
    prev_map = prev_snapshot.get("prev_holdings") or {}
    latest_saved_date = str(prev_snapshot.get("date") or "")

    # 날짜가 바뀌었을 때만 기존 스냅샷을 전일(prev)로 롤오버
    if cur_date and latest_saved_date and cur_date != latest_saved_date:
        prev_date = latest_saved_date
        prev_map = prev_snapshot.get("holdings") or {}

    if cur_map and cur_date != latest_saved_date:
        snap_file.write_text(
            json.dumps(
                {
                    "date": cur_date,
                    "holdings": cur_map,
                    "prev_date": prev_date,
                    "prev_holdings": prev_map,
                },
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )

    events: list[dict] = []
    if prev_map:
        for key in set(cur_map.keys()) | set(prev_map.keys()):
            c = cur_map.get(key)
            p = prev_map.get(key)
            c_qty = c["qty"] if c else 0.0
            p_qty = p["qty"] if p else 0.0
            c_w = c["weight"] if c else 0.0
            p_w = p["weight"] if p else 0.0
            delta_qty = c_qty - p_qty
            if p is None and c_qty > 0:
                action = "ADDED"
            elif c is None and p_qty > 0:
                action = "REMOVED"
            elif delta_qty > 0:
                action = "INCREASED"
            elif delta_qty < 0:
                action = "DECREASED"
            else:
                continue
            events.append(
                {
                    "stock_code": (c or p)["code"],
                    "stock_name": (c or p)["name"],
                    "action": action,
                    "share_delta": round(delta_qty, 2),
                    "weight_delta_pct": round(c_w - p_w, 3),
                }
            )
        events.sort(key=lambda x: abs(x["weight_delta_pct"]), reverse=True)

    return {
        "code": code,
        "date": cur_date,
        "prev_date": prev_date,
        "events": events,
    }
