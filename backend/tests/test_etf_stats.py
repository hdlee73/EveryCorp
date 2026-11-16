from datetime import date, datetime, timedelta

import pytest

from app.errors import UpstreamError
from app.parsers import etf_stats as st
from app.services import etf_stats_service as ss

MGR = {"069500": "삼성자산운용", "102110": "미래에셋자산운용", "360750": "미래에셋자산운용", "999999": ""}


def mf(code, name):
    return MGR.get(code, "")


def R(d, code, na, mc=None, name=None, shares=None):
    return (d, code, name or code, 10_000, 10_000.0, na, mc if mc is not None else na, shares)


ROWS = [
    R("2026-11-02", "069500", 100), R("2026-11-02", "102110", 50), R("2026-11-02", "999999", 5),
    R("2026-11-03", "069500", 120), R("2026-11-03", "102110", 60), R("2026-11-03", "360750", 30), R("2026-11-03", "999999", 4),
]


def test_overview_totals_and_managers():
    o = st.overview(ROWS, mf, top=1)
    assert o["dates"] == ["2026-11-02", "2026-11-03"]
    assert o["total"]["count"] == [3, 4]
    assert o["total"]["net_assets"] == [155, 214]
    # 상위 1개(미래에셋 90 > 삼성 120? 아니다: 최신 순자산 삼성 120, 미래에셋 90) + 기타
    assert [s["name"] for s in o["managers"]] == ["삼성자산운용", st.OTHERS]
    assert o["managers"][1]["count"] == [2, 3]
    t = {r["name"]: r for r in o["manager_table"]}
    assert t["미래에셋자산운용"]["count"] == 2 and t["미래에셋자산운용"]["count_change"] == 1 and t["미래에셋자산운용"]["net_assets_change"] == 40
    assert t[st.UNKNOWN_MANAGER]["count"] == 1
    assert t["삼성자산운용"]["share"] == round(120 / 214 * 100, 2)


def test_overview_empty():
    assert st.overview([], mf)["dates"] == []


def test_ranking_sort_change_and_new():
    latest = [r for r in ROWS if r[0] == "2026-11-03"]
    base = [r for r in ROWS if r[0] == "2026-11-02"]
    items = st.ranking(latest, base, mf, "net_assets_change")
    assert [i["code"] for i in items][:2] == ["069500", "102110"]
    new = next(i for i in items if i["code"] == "360750")
    assert new["new"] is True and new["net_assets_change"] is None and new["rank"] == 4  # None 은 맨 뒤
    assert next(i for i in items if i["code"] == "069500")["net_assets_change_pct"] == 20.0
    only = st.ranking(latest, base, mf, "net_assets", manager="미래에셋자산운용")
    assert [i["code"] for i in only] == ["102110", "360750"]
    # 기준일이 없으면 '신규'로 표시하지 않는다
    assert not any(i["new"] for i in st.ranking(latest, [], mf))
    # 모르는 정렬 키는 순자산으로
    assert st.ranking(latest, base, mf, "bogus")[0]["code"] == "069500"


def test_change_and_series():
    s = st.series_of([R("2026-11-03", "069500", 120), R("2026-11-02", "069500", 100)])
    assert s["dates"] == ["2026-11-02", "2026-11-03"] and s["net_assets"] == [100, 120]
    c = st.change(s["net_assets"], 5)
    assert c == {"from": 100, "to": 120, "diff": 20, "pct": 20.0}
    assert st.change([None, 5], 1) is None


# ───────── 서비스(SQLite·수집) ─────────

@pytest.fixture
def tmpdb(tmp_path, monkeypatch):
    d = ss.StatsDb(str(tmp_path / "s.sqlite"))
    monkeypatch.setattr(ss, "db", d)
    monkeypatch.setattr(ss, "FETCH_DELAY", 0)
    monkeypatch.setattr(ss, "_today", lambda: date(2026, 11, 16))

    async def basic():
        return {"069500": {"name": "KODEX 200", "manager": "삼성자산운용"}}
    monkeypatch.setattr(ss.ps, "krx_basic", basic)
    return d


def _items(code="069500", na=100):
    return {code: {"name": "KODEX 200", "close": 1, "nav": 1.0, "net_assets": na, "market_cap": na, "listed_shares": 10}}


async def test_backfill_stores_and_marks_holidays(tmpdb, monkeypatch):
    monkeypatch.setattr(ss, "BACKFILL_DAYS", 20)

    async def fetch(d):
        return ({} if d == date(2026, 11, 3) else _items(na=d.day)), "TEST"
    monkeypatch.setattr(ss, "fetch_day", fetch)
    await ss.backfill()
    known = tmpdb.known()
    assert known["2026-11-03"] == "holiday" and known["2026-11-02"] == "ok"
    cov = await ss.coverage()
    assert cov["backfill"] == "done" and cov["first_date"] == "2026-10-27" and cov["last_date"] == "2026-11-12"
    o = await ss.get_overview(30)
    assert o["total"]["count"][-1] == 1 and o["managers"][0]["name"] == "삼성자산운용"
    e = await ss.get_etf_stats("069500", 30)
    assert e["rank"] == 1 and e["net_assets"][-1] == 12 and e["manager"] == "삼성자산운용"


async def test_backfill_fails_when_history_unavailable(tmpdb, monkeypatch):
    monkeypatch.setattr(ss, "BACKFILL_DAYS", 60)

    async def fetch(d):
        raise UpstreamError("KRX", "login required")
    monkeypatch.setattr(ss, "fetch_day", fetch)
    await ss.backfill()
    cov = await ss.coverage()
    assert cov["backfill"] == "failed" and "login required" in cov["note"]


async def test_backfill_empty_run_is_failure_and_unmarked(tmpdb, monkeypatch):
    monkeypatch.setattr(ss, "BACKFILL_DAYS", 60)

    async def fetch(d):
        return {}, "TEST"
    monkeypatch.setattr(ss, "fetch_day", fetch)
    await ss.backfill()
    assert (await ss.coverage())["backfill"] == "failed"
    assert tmpdb.known() == {}  # 휴장일로 잘못 남기지 않는다
    # 실패 직후에는 다시 시도하지 않는다
    called = []

    async def fetch2(d):
        called.append(d)
        return _items(), "TEST"
    monkeypatch.setattr(ss, "fetch_day", fetch2)
    await ss.backfill()
    assert called == []


async def test_fetch_day_rejects_wrong_base_date(monkeypatch):
    async def openapi(ds):
        return {"069500": {"base_date": "20261113", "net_assets": 1}}

    async def krx_data(form):
        raise UpstreamError("KRX", "blocked")
    monkeypatch.setattr(ss, "krx_enabled", lambda: True)
    monkeypatch.setattr(ss.ps, "_krx_openapi_daily", openapi)
    monkeypatch.setattr(ss.ps, "_krx_data", krx_data)
    with pytest.raises(UpstreamError):
        await ss.fetch_day(date(2026, 1, 5))
    items, src = await ss.fetch_day(date(2026, 11, 13))
    assert items and src == "KRX Open API"


async def test_ranking_and_not_ready(tmpdb):
    tmpdb.write_day("2026-10-15", _items(na=100), "T")
    tmpdb.write_day("2026-11-13", {**_items(na=150), **_items("102110", 90)}, "T")
    r = await ss.get_ranking("net_assets", 30)
    assert r["date"] == "2026-11-13" and r["base_date"] == "2026-10-15"
    assert r["items"][0]["net_assets_change"] == 50 and r["items"][1]["new"] is True
    with pytest.raises(ss.ApiError):
        await ss.get_etf_stats("360750", 30)


async def test_etf_stats_not_ready(tmpdb):
    with pytest.raises(ss.ApiError) as e:
        await ss.get_etf_stats("069500")
    assert e.value.status == 503
