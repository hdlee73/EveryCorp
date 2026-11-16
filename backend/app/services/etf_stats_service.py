"""ETF 통계: 매일 ETF 전종목 순자산·시가총액·상장좌수를 저장하고 운용사별·일자별·ETF별로 집계한다.

- 원천은 ETF 메뉴(etf_portfolio_service)와 같은 KRX 일별 자료: KRX Open API etp/etf_bydd_trd(basDd) →
  정보데이터시스템 MDCSTAT04301(trdDd). 두 곳 모두 '기준일'을 지정하므로 과거 일자도 받을 수 있다(백필).
- 저장소는 SQLite(data/etf_stats.sqlite). 하루치는 ETF 약 1천 건이라 1년이면 25만 행 정도다.
- 장 마감 뒤 순자산이 확정되지 않았을 수 있어 '오늘'은 받지 않고 전 영업일까지만 받는다.
- 과거 일자를 원천이 주지 않으면(실패·과거일자 무시) 백필을 멈추고 '첫 수집일부터 누적'으로 안내한다.
- 운용사는 현재 KRX 기본정보(COM_ABBRV) → 브랜드명 추정 순으로 붙인다(상장폐지된 ETF 는 브랜드로만 추정).
- 개인 계좌 정보는 다루지 않는다. 키움 호출은 하지 않는다.
"""
from __future__ import annotations

import asyncio
import logging
import os
import sqlite3
import time
from contextlib import contextmanager
from datetime import date, datetime, timedelta, timezone

from ..errors import ApiError, UpstreamError
from ..parsers import etf_pdf as xp
from ..parsers import etf_stats as st
from . import etf_portfolio_service as ps
from . import etf_service
from .krx_client import krx_enabled

log = logging.getLogger(__name__)

KST = timezone(timedelta(hours=9))
DB_PATH = "data/etf_stats.sqlite"
BACKFILL_DAYS = 370        # 달력 기준 약 1년
FETCH_DELAY = 0.7          # 초. KRX 에 부담을 주지 않도록 한 날씩 천천히
RECENT_DAYS = 12           # 매 시간 빠진 날이 없는지 다시 보는 최근 범위(달력일)
HOLIDAY_AFTER = 3          # 이 일수보다 지난 날에 자료가 비어 있으면 휴장일로 기록
MAX_ERRORS = 5             # 백필 중 연속 실패가 이만큼이면 멈춤
MAX_EMPTY_RUN = 15         # 평일이 연속 이만큼 비면(휴장일은 길어야 열흘 남짓) 원천이 과거일자를 안 주는 것으로 판단
RETRY_FAILED_AFTER = 24 * 3600

SCHEMA = """
CREATE TABLE IF NOT EXISTS snap(
  date TEXT NOT NULL, code TEXT NOT NULL, name TEXT, close INTEGER, nav REAL,
  net_assets INTEGER, market_cap INTEGER, listed_shares INTEGER,
  PRIMARY KEY(date, code));
CREATE INDEX IF NOT EXISTS snap_code ON snap(code, date);
CREATE TABLE IF NOT EXISTS days(date TEXT PRIMARY KEY, status TEXT NOT NULL, count INTEGER, source TEXT, fetched_at REAL);
CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT);
"""


def _today() -> date:
    return datetime.now(KST).date()


def _iso(d: date) -> str:
    return d.isoformat()


# ───────────────────────── 저장소(SQLite, 스레드에서 호출) ─────────────────────────

class StatsDb:
    def __init__(self, path: str = DB_PATH) -> None:
        self.path = path
        self.version = 0   # 쓰기마다 증가(집계 캐시 무효화용)
        self._ready = False

    def _conn(self) -> sqlite3.Connection:
        if not self._ready:
            os.makedirs(os.path.dirname(self.path) or ".", exist_ok=True)
        c = sqlite3.connect(self.path, timeout=30)
        if not self._ready:
            c.execute("PRAGMA journal_mode=WAL")
            c.executescript(SCHEMA)
            self._ready = True
        return c

    @contextmanager
    def _use(self):
        """트랜잭션(성공하면 커밋) 후 연결을 닫는다. sqlite3 의 with 문은 커밋만 하고 닫지 않는다."""
        c = self._conn()
        try:
            with c:
                yield c
        finally:
            c.close()

    def write_day(self, d: str, items: dict[str, dict], source: str) -> None:
        rows = [(d, code, it.get("name") or "", it.get("close"), it.get("nav"), it.get("net_assets"), it.get("market_cap"), it.get("listed_shares"))
                for code, it in items.items()]
        with self._use() as c:
            c.execute("DELETE FROM snap WHERE date=?", (d,))
            c.executemany("INSERT INTO snap VALUES (?,?,?,?,?,?,?,?)", rows)
            c.execute("INSERT OR REPLACE INTO days VALUES (?,?,?,?,?)", (d, "ok", len(rows), source, time.time()))
        self.version += 1

    def mark(self, d: str, status: str) -> None:
        with self._use() as c:
            c.execute("INSERT OR REPLACE INTO days VALUES (?,?,?,?,?)", (d, status, 0, "", time.time()))

    def unmark(self, dates: list[str]) -> None:
        if not dates:
            return
        with self._use() as c:
            c.executemany("DELETE FROM days WHERE date=? AND status='holiday'", [(d,) for d in dates])

    def known(self) -> dict[str, str]:
        with self._use() as c:
            return dict(c.execute("SELECT date, status FROM days"))

    def get_meta(self, key: str, default: str = "") -> str:
        with self._use() as c:
            r = c.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
        return r[0] if r else default

    def set_meta(self, **kv: str) -> None:
        with self._use() as c:
            c.executemany("INSERT OR REPLACE INTO meta VALUES (?,?)", [(k, str(v)) for k, v in kv.items()])

    def span(self) -> tuple[str, str, int, str]:
        """(첫날, 마지막날, 저장한 영업일 수, 마지막날 원천)."""
        with self._use() as c:
            r = c.execute("SELECT MIN(date), MAX(date), COUNT(*) FROM days WHERE status='ok'").fetchone()
            src = c.execute("SELECT source FROM days WHERE status='ok' ORDER BY date DESC LIMIT 1").fetchone()
        return (r[0] or "", r[1] or "", r[2] or 0, src[0] if src else "")

    def overview_since(self, start: str, manager_of, top: int) -> dict:
        """커서를 그대로 집계 함수에 흘려 넣는다(1년치 25만 행을 메모리에 다 올리지 않게)."""
        c = self._conn()
        try:
            cur = c.execute("SELECT date, code, name, close, nav, net_assets, market_cap, listed_shares FROM snap WHERE date>=? ORDER BY date", (start,))
            return st.overview(cur, manager_of, top)
        finally:
            c.close()

    def rows_on(self, d: str) -> list[tuple]:
        with self._use() as c:
            return c.execute("SELECT date, code, name, close, nav, net_assets, market_cap, listed_shares FROM snap WHERE date=?", (d,)).fetchall()

    def rows_of(self, code: str, start: str) -> list[tuple]:
        with self._use() as c:
            return c.execute("SELECT date, code, name, close, nav, net_assets, market_cap, listed_shares FROM snap WHERE code=? AND date>=? ORDER BY date", (code, start)).fetchall()

    def ok_day_on_or_before(self, d: str) -> str:
        with self._use() as c:
            r = c.execute("SELECT MAX(date) FROM days WHERE status='ok' AND date<=?", (d,)).fetchone()
            if not r or not r[0]:
                r = c.execute("SELECT MIN(date) FROM days WHERE status='ok'").fetchone()
        return (r[0] if r else "") or ""


db = StatsDb()


async def _db(fn, *a, **kw):
    return await asyncio.to_thread(fn, *a, **kw)


# ───────────────────────── 원천 ─────────────────────────

def _base_ok(items: dict[str, dict], ds: str) -> bool:
    """원천이 요청한 날짜를 무시하고 최신 자료를 돌려주는 경우를 걸러 낸다(BAS_DD 가 있을 때만 판단)."""
    bases = {it.get("base_date", "").replace("/", "").replace("-", "") for it in items.values() if it.get("base_date")}
    return not bases or ds in bases


async def fetch_day(d: date) -> tuple[dict[str, dict], str]:
    """그날 ETF 전종목 자료와 원천 이름. 원천이 정상 응답했는데 비어 있으면 ({}, 원천). 모두 실패하면 UpstreamError."""
    ds = d.strftime("%Y%m%d")
    errors: list[str] = []
    empty_src = ""
    if krx_enabled():
        try:
            items = await ps._krx_openapi_daily(ds)
            if items and _base_ok(items, ds):
                return items, "KRX Open API"
            if items:
                errors.append("Open API 가 요청한 날짜가 아닌 자료를 보냄")
            else:
                empty_src = "KRX Open API"
        except Exception as e:  # noqa: BLE001
            errors.append(f"Open API: {e}")
    try:
        items = xp.parse_krx_daily_all(await ps._krx_data({"bld": "dbms/MDC/STAT/standard/MDCSTAT04301", "trdDd": ds, "share": "1", "money": "1"}))
        if items and _base_ok(items, ds):
            return items, "KRX 정보데이터시스템"
        if items:
            errors.append("정보데이터시스템이 요청한 날짜가 아닌 자료를 보냄")
        else:
            empty_src = empty_src or "KRX 정보데이터시스템"
    except Exception as e:  # noqa: BLE001
        errors.append(f"정보데이터시스템: {e}")
    if empty_src:
        return {}, empty_src
    raise UpstreamError("KRX", "; ".join(errors) or "응답 없음")


def _weekdays(start: date, end: date) -> list[date]:
    out, d = [], start
    while d <= end:
        if d.weekday() < 5:
            out.append(d)
        d += timedelta(days=1)
    return out


# ───────────────────────── 수집 ─────────────────────────

_state = {"collecting": False, "progress": (0, 0)}


async def collect_recent() -> int:
    """최근 RECENT_DAYS 안에서 빠진 영업일(어제까지)을 채운다. 새로 저장한 날 수."""
    today = _today()
    if datetime.now(KST).hour < 9:
        end = today - timedelta(days=2)   # Open API 는 전일 자료를 아침 8시께 올린다
    else:
        end = today - timedelta(days=1)
    known = await _db(db.known)
    saved = 0
    for d in reversed(_weekdays(today - timedelta(days=RECENT_DAYS), end)):
        if _iso(d) in known:
            continue
        try:
            items, src = await fetch_day(d)
        except Exception as e:  # noqa: BLE001
            log.info("ETF stats recent %s: %s", d, e)
            break
        if items:
            await _db(db.write_day, _iso(d), items, src)
            saved += 1
        elif (today - d).days > HOLIDAY_AFTER:
            await _db(db.mark, _iso(d), "holiday")
        await asyncio.sleep(FETCH_DELAY)
    return saved


async def backfill() -> None:
    """과거 약 1년치를 최근 날짜부터 거꾸로 채운다. 원천이 과거일자를 주지 않으면 failed 로 멈추고 하루 뒤 다시 시도."""
    state = await _db(db.get_meta, "backfill_state", "pending")
    if state == "failed":
        failed_at = float(await _db(db.get_meta, "backfill_failed_at", "0") or 0)
        if time.time() - failed_at < RETRY_FAILED_AFTER:
            return
    today = _today()
    known = await _db(db.known)
    todo = [d for d in reversed(_weekdays(today - timedelta(days=BACKFILL_DAYS), today - timedelta(days=HOLIDAY_AFTER + 1))) if _iso(d) not in known]
    if not todo:
        if state != "done":
            await _db(db.set_meta, backfill_state="done", backfill_message="")
        return
    await _db(db.set_meta, backfill_state="running", backfill_message="")
    _state["collecting"] = True
    _state["progress"] = (0, len(todo))
    errors, empty_run, empties = 0, 0, []
    try:
        for i, d in enumerate(todo, 1):
            try:
                items, src = await fetch_day(d)
            except Exception as e:  # noqa: BLE001
                errors += 1
                log.info("ETF stats backfill %s: %s", d, e)
                if errors >= MAX_ERRORS:
                    await _db(db.set_meta, backfill_state="failed", backfill_failed_at=str(time.time()),
                              backfill_message=f"KRX 가 과거 일자 요청에 응답하지 않았습니다({e}).")
                    return
                await asyncio.sleep(FETCH_DELAY * 3)
                continue
            errors = 0
            if items:
                empty_run = 0
                await _db(db.write_day, _iso(d), items, src)
            else:
                empty_run += 1
                empties.append(_iso(d))
                await _db(db.mark, _iso(d), "holiday")
                if empty_run >= MAX_EMPTY_RUN:
                    # 휴장일로 잘못 기록한 날들을 되돌려 다음에 다시 시도하게 한다
                    await _db(db.unmark, empties[-empty_run:])
                    await _db(db.set_meta, backfill_state="failed", backfill_failed_at=str(time.time()),
                              backfill_message="KRX 가 과거 일자 자료를 비워서 보냈습니다(과거 조회 미지원으로 판단).")
                    return
            _state["progress"] = (i, len(todo))
            await asyncio.sleep(FETCH_DELAY)
        await _db(db.set_meta, backfill_state="done", backfill_message="")
        log.info("ETF stats backfill finished: %d days", len(todo))
    finally:
        _state["collecting"] = False


async def refresh_loop() -> None:
    await asyncio.sleep(150)  # 서버 시작 직후 다른 초기화(ETF PDF 수집 등)와 겹치지 않게
    while True:
        try:
            await collect_recent()
            await backfill()
        except Exception:  # noqa: BLE001
            log.exception("ETF stats collect failed")
        await asyncio.sleep(3600)


# ───────────────────────── 화면용 응답 ─────────────────────────

async def coverage() -> dict:
    first, last, n, src = await _db(db.span)
    state = await _db(db.get_meta, "backfill_state", "pending")
    msg = await _db(db.get_meta, "backfill_message", "")
    done, total = _state["progress"]
    if not n:
        note = "아직 저장된 자료가 없습니다. 서버가 KRX 에서 자료를 받는 중이거나(서버 시작 몇 분 뒤 시작) KRX 접속이 막혀 있습니다." + (f" ({msg})" if msg else "")
    elif state == "failed":
        note = f"KRX 에서 과거 일자 자료를 받지 못해 {first}부터 매일 쌓고 있습니다. 추이는 날이 갈수록 길어집니다. ({msg})"
    elif state == "running":
        note = f"과거 1년치를 KRX 에서 받는 중입니다({done}/{total}일). 다 받기 전에는 추이가 짧거나 중간이 비어 보일 수 있습니다."
    else:
        note = f"{first} ~ {last}, {n}영업일 자료입니다. 매일 전 영업일 자료를 더합니다."
    return {
        "first_date": first,
        "last_date": last,
        "days": n,
        "source": src,
        "backfill": state,
        "progress": {"done": done, "total": total},
        "since_first_collect": state == "failed",
        "collecting": _state["collecting"],
        "note": note,
    }


async def _manager_fn():
    basic = await ps.krx_basic()
    display: dict[str, str] = {}

    def canon(m: str) -> str:
        # 같은 운용사를 '삼성자산운용(주)'·'삼성자산운용' 처럼 다르게 적은 것을 하나로
        if not m:
            return ""
        return display.setdefault(xp.manager_key(m), m)

    for b in basic.values():  # KRX 표기를 먼저 등록해 대표 이름으로 쓴다
        canon(b.get("manager", ""))

    def fn(code: str, name: str) -> str:
        b = basic.get(code) or {}
        return canon(xp.manager_of(name or b.get("name", ""), b.get("manager", ""))[0])

    return fn


_overview_cache: dict = {}


def _start(days: int) -> str:
    return _iso(_today() - timedelta(days=days))


async def get_overview(days: int = 365, top: int = 8) -> dict:
    cov = await coverage()
    key = (days, top, db.version, cov["last_date"], _today())
    hit = _overview_cache.get(key)
    if hit is None:
        fn = await _manager_fn()
        hit = await _db(db.overview_since, _start(days), fn, top)
        if len(_overview_cache) >= 8:
            _overview_cache.clear()
        _overview_cache[key] = hit
    return {
        **hit,
        "coverage": cov,
        "note": "ETF 수·순자산총액·시가총액은 KRX 일별 자료(장 마감 기준)입니다. 운용사는 현재 KRX 기본정보 기준이며, "
                "상장폐지된 ETF 는 브랜드명으로 추정합니다. 순자산 상위 운용사 외에는 '기타'로 묶습니다.",
    }


async def get_ranking(sort: str = "net_assets", period: int = 30, limit: int = 100, manager: str = "") -> dict:
    cov = await coverage()
    last = cov["last_date"]
    if not last:
        return {"date": "", "base_date": "", "total": 0, "items": [], "managers": [], "coverage": cov, "note": cov["note"]}
    base = await _db(db.ok_day_on_or_before, _iso(date.fromisoformat(last) - timedelta(days=period)))
    latest_rows, base_rows = await asyncio.gather(_db(db.rows_on, last), _db(db.rows_on, base))
    fn = await _manager_fn()
    items = st.ranking(latest_rows, base_rows if base != last else [], fn, sort, manager)
    managers = sorted({m for m in (fn(r[1], r[2] or "") for r in latest_rows) if m})
    return {
        "date": last,
        "base_date": base if base != last else "",
        "sort": sort if sort in st.SORTS else "net_assets",
        "total": len(items),
        "items": items[:limit],
        "managers": managers,
        "coverage": cov,
        "note": "변화는 기준일 대비입니다. 기준일 이후 상장한 ETF 는 '신규'로 표시하고 변화를 비워 둡니다.",
    }


async def get_etf_stats(code: str, days: int = 365) -> dict:
    code = etf_service.check_code(code)
    cov = await coverage()
    rows = await _db(db.rows_of, code, _start(days))
    if not rows:
        if cov["days"]:
            raise ApiError(404, "ETF_STATS_EMPTY", "저장된 일별 자료에 이 ETF 가 없습니다(최근 상장했거나 상장폐지된 종목일 수 있습니다).")
        raise ApiError(503, "ETF_STATS_NOT_READY", cov["note"])
    s = st.series_of(rows)
    fn = await _manager_fn()
    name = rows[-1][2] or code
    latest = await _db(db.rows_on, s["dates"][-1])
    ranked = st.ranking(latest, [], fn, "net_assets")
    rank = next((it["rank"] for it in ranked if it["code"] == code), None)
    mgr = fn(code, name)
    mgr_ranked = [it for it in ranked if it["manager"] == mgr] if mgr else []
    changes = []
    for label, back in (("1주", 5), ("1개월", 21), ("3개월", 63), ("6개월", 126), ("1년", 250)):
        if len(s["dates"]) > back // 2:  # 기간의 절반도 안 되는 자료로는 비교하지 않는다
            changes.append({"label": label, "net_assets": st.change(s["net_assets"], back), "market_cap": st.change(s["market_cap"], back),
                            "listed_shares": st.change(s["listed_shares"], back)})
    return {
        "code": code,
        "name": name,
        "manager": mgr,
        **s,
        "rank": rank,
        "rank_total": len(ranked),
        "manager_rank": next((i for i, it in enumerate(mgr_ranked, 1) if it["code"] == code), None),
        "manager_total": len(mgr_ranked),
        "changes": changes,
        "coverage": cov,
        "note": "순자산총액·시가총액·상장좌수는 KRX 일별 자료(장 마감 기준)입니다. 순위는 마지막 날 전체 ETF 순자산 기준입니다.",
    }
