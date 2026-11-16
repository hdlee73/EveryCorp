from __future__ import annotations

from pathlib import Path
from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient

from app.main import create_app


def _client() -> TestClient:
    return TestClient(create_app(start_background=False))


def test_etf_live_metrics_endpoint(monkeypatch):
    monkeypatch.setenv("APP_TOKEN", "")
    fake_etf = {
        "code": "091180",
        "name": "KODEX 자동차",
        "quote": {"price": 22390},
        "daily": [{"nav": 22180.0, "tracking_error": 0.41}],
        "basics": {"nav_krx": 22180.0, "total_fee": 0.45},
    }
    fake_ob = {"tot_sel_req": "320", "tot_buy_req": "410"}
    with (
        patch("app.services.etf_service.get_etf", new=AsyncMock(return_value=fake_etf)),
        patch("app.services.kiwoom_client.orderbook", new=AsyncMock(return_value=fake_ob)),
    ):
        res = _client().get("/api/v1/etfs/091180/live")
        assert res.status_code == 200
        body = res.json()
        assert body["code"] == "091180"
        assert body["warning_level"] == "OVERHEAT_ALERT"
        assert body["lp_ask_qty"] == 320


def test_etf_real_costs_endpoint(monkeypatch):
    monkeypatch.setenv("APP_TOKEN", "")
    fake_etf = {
        "code": "069500",
        "name": "KODEX 200",
        "basics": {"total_fee": 0.15, "asset_class": "국내주식"},
    }
    with patch("app.services.etf_service.get_etf", new=AsyncMock(return_value=fake_etf)):
        res = _client().get("/api/v1/etfs/069500/costs?investment_won=10000000")
        assert res.status_code == 200
        body = res.json()
        assert body["real_cost_pct"] == 0.2
        assert body["annual_cost_won"] == 20000


def test_etf_rebalancing_endpoint(tmp_path: Path, monkeypatch):
    monkeypatch.setenv("APP_TOKEN", "")
    monkeypatch.setenv("ETF_SNAPSHOT_DIR", str(tmp_path))

    day1 = {
        "code": "441640",
        "date": "2026-11-15",
        "holdings": [{"code": "MSFT", "name": "Microsoft", "qty": 460, "weight": 5.8}],
    }
    day2 = {
        "code": "441640",
        "date": "2026-11-16",
        "holdings": [
            {"code": "MSFT", "name": "Microsoft", "qty": 520, "weight": 6.5},
            {"code": "LLY", "name": "Eli Lilly", "qty": 140, "weight": 3.1},
        ],
    }

    with patch("app.services.etf_portfolio_service.get_portfolio", new=AsyncMock(return_value=day1)):
        r1 = _client().get("/api/v1/etfs/441640/rebalancing")
        assert r1.status_code == 200
        assert r1.json()["events"] == []

    with patch("app.services.etf_portfolio_service.get_portfolio", new=AsyncMock(return_value=day2)):
        r2 = _client().get("/api/v1/etfs/441640/rebalancing")
        assert r2.status_code == 200
        events = {e["stock_code"]: e for e in r2.json()["events"]}
        assert events["MSFT"]["action"] == "INCREASED"
        assert events["LLY"]["action"] == "ADDED"
