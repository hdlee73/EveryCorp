"""ETF 기초자료(순자산·상장좌수·운용사·총보수), 구성종목(PDF), ETF 간 관련성.

- 키움 REST API 에는 구성종목 TR 이 없어 KRX 자료를 쓴다(키움 호출 허용 목록은 바꾸지 않는다).
- 구성종목은 ETF 단위로 디스크(data/etf_pdf.json)에 저장해 '이 ETF를 담은 ETF'·'구성이 비슷한 ETF'·순환투자를 교차 분석한다.
  저장소는 하루 한 번(평일 08시 이후) 전 ETF 를 천천히 다시 받는다. 화면에서 조회한 ETF 는 즉시 저장한다.
- 매칭 보정: 구성종목 코드(단축코드·ISIN)·우선주·종목명 정규화로 DART 상장사/ETF 와 대조하고, 못 찾은 항목은 UNMATCHED(앱 '미매칭').
- 개인 계좌 정보는 다루지 않는다.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import time
from datetime import date, datetime, timedelta, timezone

from ..cache import cached
from ..config import get_settings
from ..errors import ApiError, UpstreamError
from ..http import get_client
from ..parsers import etf_pdf as xp
from . import etf_service
from .corp_index import corp_index
from .krx_client import krx_enabled
from .kiwoom_client import kiwoom_enabled

log = logging.getLogger(__name__)

KST = timezone(timedelta(hours=9))
KRX_DATA_URL = "https://data.krx.co.kr/comm/bldAttendant/getJsonData.cmd"
KRX_REFERER = "https://data.krx.co.kr/contents/MDC/MDI/mdiLoader/index.cmd?menuId=MDC0201030101"
NAVER_ETF_URL = "https://m.stock.naver.com/api/stock/{code}/etfAnalysis"
STORE_PATH = "data/etf_pdf.json"
CRAWL_DELAY = 0.5        # 초. KRX 에 부담을 주지 않도록 한 건씩 천천히
MAX_CYCLE_FETCH = 25     # 순환투자 탐색 때 새로 받아 올 하위 ETF 최대 수

SOURCE_KRX_PDF = "KRX 정보데이터시스템 ETF PDF"
SOURCE_NAVER = "네이버 증권 ETF 분석(상위 구성종목만)"

KIND_LABEL = {"STOCK": "주식", "ETF": "ETF", "BOND": "채권", "CASH": "현금성", "DERIVATIVE": "파생", "FOREIGN": "해외자산", "UNMATCHED": "미매칭"}


def _today_kst() -> date:
    return datetime.now(KST).date()


def _recent_business_days(n: int = 7) -> list[str]:
    d, out = _today_kst(), []
    while len(out) < n:
        if d.weekday() < 5:
            out.append(d.strftime("%Y%m%d"))
        d -= timedelta(days=1)
    return out


# ───────────────────────── 원천 호출 ─────────────────────────

async def _krx_data(form: dict) -> dict:
    """KRX 정보데이터시스템 JSON. 로그인 요구·차단 시 HTML/텍스트가 와서 UpstreamError."""
    r = await get_client().post(
        KRX_DATA_URL,
        data={"locale": "ko_KR", "csvxls_isNo": "false", **form},
        headers={"Referer": KRX_REFERER, "X-Requested-With": "XMLHttpRequest"},
    )
    if r.status_code != 200:
        raise UpstreamError("KRX", f"정보데이터시스템 HTTP {r.status_code}")
    try:
        data = r.json()
    except ValueError as e:
        raise UpstreamError("KRX", "정보데이터시스템이 JSON 대신 다른 응답(로그인 요구 등)을 보냈습니다.") from e
    if not isinstance(data, dict):
        raise UpstreamError("KRX", "정보데이터시스템 응답 형식이 다릅니다.")
    return data


@cached(ttl=12 * 3600)
async def _krx_basic_loaded() -> dict[str, dict]:
    data = xp.parse_krx_basic(await _krx_data({"bld": "dbms/MDC/STAT/standard/MDCSTAT04601", "share": "1"}))
    if not data:
        raise UpstreamError("KRX", "ETF 기본정보가 비어 있습니다.")
    return data


_basic_failed_at = 0.0


async def krx_basic() -> dict[str, dict]:
    """ETF 전종목 기본정보(운용사·총보수·ISIN·상장일). 실패하면 빈 dict(브랜드 추정으로 대체)하고 10분 동안 다시 시도하지 않는다."""
    global _basic_failed_at
    if time.time() - _basic_failed_at < 600:
        return {}
    try:
        return await _krx_basic_loaded()
    except Exception as e:  # noqa: BLE001
        _basic_failed_at = time.time()
        log.info("KRX ETF basic unavailable: %s", e)
        return {}


async def _krx_openapi_daily(bas_dd: str) -> dict[str, dict]:
    s = get_settings()
    r = await get_client().get(f"{s.krx_base_url}/etp/etf_bydd_trd", params={"basDd": bas_dd}, headers={"AUTH_KEY": s.krx_auth_key})
    if r.status_code != 200:
        raise UpstreamError("KRX", f"Open API HTTP {r.status_code}")
    return xp.parse_krx_daily_all(r.json())


@cached(ttl=3600)
async def krx_daily_all() -> dict:
    """가장 최근 영업일의 ETF 전종목 순자산·상장좌수·NAV. {'source','items'}. 둘 다 실패하면 items 빈 dict."""
    days = _recent_business_days(6)
    if krx_enabled():
        for d in days:
            try:
                items = await _krx_openapi_daily(d)
            except Exception as e:  # noqa: BLE001
                log.info("KRX OpenAPI etf_bydd_trd %s: %s", d, e)
                break
            if items:
                return {"source": "KRX Open API(ETF 일별매매정보)", "items": items}
    for d in days[:3]:
        try:
            items = xp.parse_krx_daily_all(await _krx_data({"bld": "dbms/MDC/STAT/standard/MDCSTAT04301", "trdDd": d, "share": "1", "money": "1"}))
        except Exception as e:  # noqa: BLE001
            log.info("KRX ETF daily %s: %s", d, e)
            break
        if items:
            return {"source": "KRX 정보데이터시스템(ETF 전종목 시세)", "items": items}
    return {"source": "", "items": {}}


async def _isin(code: str) -> str:
    b = (await krx_basic()).get(code)
    return (b or {}).get("isin") or xp.kr_isin(code)


_krx_pdf_down_until = 0.0  # KRX 가 거부(로그인 요구 등)하면 한 시간 동안은 바로 대체 원천으로 넘어간다


async def _pdf_from_krx(code: str) -> dict | None:
    global _krx_pdf_down_until
    if time.time() < _krx_pdf_down_until:
        return None
    isin = await _isin(code)
    for d in _recent_business_days(4):
        try:
            data = await _krx_data({"bld": "dbms/MDC/STAT/standard/MDCSTAT05001", "trdDd": d, "isuCd": isin, "share": "1", "money": "1"})
        except UpstreamError:
            _krx_pdf_down_until = time.time() + 3600
            raise
        rows = xp.parse_krx_pdf(data)
        if rows:
            return {"date": f"{d[:4]}-{d[4:6]}-{d[6:]}", "source": SOURCE_KRX_PDF, "partial": False, "holdings": xp.fill_weights(rows)}
    return None


async def _naver(code: str) -> dict:
    r = await get_client().get(NAVER_ETF_URL.format(code=code), headers={"Referer": "https://m.stock.naver.com/"})
    if r.status_code != 200:
        raise UpstreamError("네이버", f"HTTP {r.status_code}")
    return r.json()


@cached(ttl=6 * 3600)
async def _naver_cached(code: str) -> dict:
    return await _naver(code)


async def _pdf_from_naver(code: str) -> dict | None:
    rows = xp.parse_naver_holdings(await _naver(code))
    if not rows:
        return None
    return {"date": _today_kst().isoformat(), "source": SOURCE_NAVER, "partial": True, "holdings": rows}


async def _fetch_pdf(code: str) -> dict | None:
    errors = []
    for fn in (_pdf_from_krx, _pdf_from_naver):
        try:
            got = await fn(code)
        except Exception as e:  # noqa: BLE001
            errors.append(f"{fn.__name__}: {e}")
            continue
        if got:
            return got
    if errors:
        log.info("ETF PDF %s failed: %s", code, "; ".join(errors))
    return None


# ───────────────────────── 저장소 ─────────────────────────

class PdfStore:
    def __init__(self) -> None:
        self.items: dict[str, dict] = {}
        self.crawled_on: str = ""      # 전체 수집을 마친 날(KST, YYYY-MM-DD)
        self.crawl_progress: tuple[int, int] = (0, 0)
        self.crawling = False
        self._dirty = 0
        self.version = 0               # 바뀔 때마다 증가(매칭 결과 캐시 무효화용)

    def load(self, path: str = STORE_PATH) -> None:
        if not os.path.exists(path):
            return
        try:
            with open(path, encoding="utf-8") as f:
                raw = json.load(f)
            self.items = {k: v for k, v in (raw.get("items") or {}).items() if isinstance(v, dict) and isinstance(v.get("holdings"), list)}
            self.crawled_on = raw.get("crawled_on", "")
            self.version += 1
        except Exception:  # noqa: BLE001
            log.exception("ETF PDF store load failed")

    def save(self, path: str = STORE_PATH) -> None:
        try:
            os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
            tmp = path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump({"saved_at": time.time(), "crawled_on": self.crawled_on, "items": self.items}, f, ensure_ascii=False)
            os.replace(tmp, path)
            self._dirty = 0
        except Exception:  # noqa: BLE001
            log.exception("ETF PDF store save failed")

    def put(self, code: str, entry: dict) -> None:
        self.items[code] = entry
        self.version += 1
        self._dirty += 1

    def fresh(self, code: str, max_age_days: int = 1) -> dict | None:
        e = self.items.get(code)
        if not e:
            return None
        try:
            age = (_today_kst() - date.fromisoformat(e.get("fetched_on", "1970-01-01"))).days
        except ValueError:
            return None
        return e if age < max_age_days else None


store = PdfStore()
_fetch_locks: dict[str, asyncio.Lock] = {}


async def get_pdf(code: str, allow_stale: bool = False) -> dict | None:
    """오늘 받은 것이 저장소에 있으면 그것, 없으면 새로 받아 저장. allow_stale 이면 오래된 것도 그대로 쓴다."""
    hit = store.items.get(code) if allow_stale else store.fresh(code)
    if hit:
        return hit
    lock = _fetch_locks.setdefault(code, asyncio.Lock())
    async with lock:
        hit = store.fresh(code)
        if hit:
            return hit
        got = await _fetch_pdf(code)
        if got is None:
            return store.items.get(code)  # 새로 못 받으면 예전 것이라도
        got["holdings"] = [{k: h.get(k) for k in ("code", "name", "qty", "value", "weight") if h.get(k) not in (None, "")} for h in got["holdings"]]
        got["fetched_on"] = _today_kst().isoformat()
        store.put(code, got)
        if store._dirty >= 1 and not store.crawling:
            store.save()
        return got


# ───────────────────────── 매칭 ─────────────────────────

async def etf_names() -> dict[str, str]:
    names = {c: b["name"] for c, b in (await krx_basic()).items() if b.get("name")}
    if kiwoom_enabled():
        try:
            for it in await etf_service.list_etfs():
                names.setdefault(it["code"], it["name"])
        except Exception as e:  # noqa: BLE001
            log.info("Kiwoom ETF list for matching failed: %s", e)
    return names


_matcher_cache: dict = {"at": 0.0, "m": None, "n": 0}


async def matcher() -> xp.Matcher:
    """한 시간 동안 재사용. DART 색인이 바뀌면(항목 수 변화) 다시 만든다."""
    n = len(corp_index.by_stock)
    if _matcher_cache["m"] is None or time.time() - _matcher_cache["at"] > 3600 or _matcher_cache["n"] != n:
        corp = {code: (e.corp_code, e.corp_name) for code, e in corp_index.by_stock.items()}
        _matcher_cache.update(m=xp.Matcher(corp, await etf_names()), at=time.time(), n=n)
    return _matcher_cache["m"]


_matched_cache: dict = {"v": -1, "m": None, "data": {}}


async def matched_store() -> dict[str, dict]:
    """저장소 전체를 매칭해 가벼운 형태(xp.compact)로 둔다(관련성 교차 분석용). 저장소나 매처가 바뀌면 다시 계산."""
    m = await matcher()
    if _matched_cache["v"] != store.version or _matched_cache["m"] is not m:
        data = {}
        for i, (c, e) in enumerate(list(store.items.items())):
            data[c] = xp.compact(xp.match_holdings(e["holdings"], m))
            if i % 200 == 199:
                await asyncio.sleep(0)  # 오래 걸려도 다른 요청을 막지 않게
        _matched_cache.update(v=store.version, m=m, data=data)
    return _matched_cache["data"]


async def manager_lookup_fn():
    basic = await krx_basic()
    names = await etf_names()

    def lookup(code: str, name: str = "") -> str:
        b = basic.get(code) or {}
        return xp.manager_of(name or b.get("name") or names.get(code, ""), b.get("manager", ""))[0]

    return lookup


# ───────────────────────── 화면용 응답 ─────────────────────────

async def get_basics(code: str, name: str = "", kiwoom_nav: float | None = None, kiwoom_name: str = "") -> dict:
    """순자산총액·상장좌수·NAV(KRX)·운용사·총보수 + 키움 자료와의 대조 결과. 모든 항목은 실패해도 None."""
    basic = (await krx_basic()).get(code) or {}
    daily = await krx_daily_all()
    dd = daily["items"].get(code) or {}
    manager, manager_src = xp.manager_of(name or basic.get("name", "") or dd.get("name", ""), basic.get("manager", ""))
    fee = basic.get("total_fee")
    fee_src = "KRX 기본정보" if fee is not None else ""
    if fee is None:
        try:
            fee = xp.parse_naver_fee(await _naver_cached(code))
            fee_src = "네이버 증권" if fee is not None else ""
        except Exception as e:  # noqa: BLE001
            log.info("Naver ETF fee %s: %s", code, e)
    net_assets, shares, nav = dd.get("net_assets"), dd.get("listed_shares"), dd.get("nav")
    computed = round(nav * shares) if nav and shares else None
    checks = []
    if computed and net_assets:
        diff = (computed - net_assets) / net_assets * 100
        checks.append({"label": "순자산 = NAV × 상장좌수", "ok": abs(diff) < 1.0, "detail": f"계산값과 공시값 차이 {diff:+.2f}%"})
    if kiwoom_nav and nav:
        diff = (kiwoom_nav - nav) / nav * 100
        checks.append({"label": "NAV 키움 ↔ KRX", "ok": abs(diff) < 0.5, "detail": f"키움 {kiwoom_nav:,.2f} · KRX {nav:,.2f} ({diff:+.2f}%, 기준일이 다를 수 있음)"})
    krx_name = basic.get("name") or dd.get("name") or ""
    if kiwoom_name and krx_name:
        same = xp.norm(kiwoom_name) == xp.norm(krx_name)
        checks.append({"label": "종목명 키움 ↔ KRX", "ok": same, "detail": kiwoom_name if same else f"키움 '{kiwoom_name}' · KRX '{krx_name}'"})
    return {
        "base_date": _fmt_date(dd.get("base_date", "")),
        "net_assets": net_assets if net_assets else computed,
        "net_assets_computed": not net_assets and computed is not None,
        "listed_shares": shares,
        "market_cap": dd.get("market_cap"),
        "nav_krx": nav,
        "manager": manager,
        "manager_source": {"krx": "KRX 기본정보", "brand": "브랜드명으로 추정"}.get(manager_src, ""),
        "total_fee": fee,
        "fee_source": fee_src,
        "listed_date": basic.get("listed_date", ""),
        "replication": basic.get("replication", ""),
        "asset_class": basic.get("asset_class", ""),
        "isin": basic.get("isin") or xp.kr_isin(code),
        "checks": checks,
        "source": daily["source"],
    }


def _fmt_date(s: str) -> str:
    s = (s or "").replace("/", "").replace("-", "")
    return f"{s[:4]}-{s[4:6]}-{s[6:8]}" if len(s) == 8 and s.isdigit() else ""


def _public_holding(h: dict) -> dict:
    keep = ("code", "name", "qty", "value", "weight", "kind", "match", "corp_code", "corp_name", "stock_code", "etf_code", "etf_name")
    return {k: h.get(k) for k in keep if h.get(k) not in (None, "")} | {"kind_label": KIND_LABEL.get(h.get("kind", ""), "미매칭")}


def _by_kind(holdings: list[dict]) -> list[dict]:
    agg: dict[str, float] = {}
    for h in holdings:
        agg[h["kind"]] = agg.get(h["kind"], 0.0) + (h.get("weight") or 0.0)
    return [{"kind": k, "label": KIND_LABEL.get(k, k), "weight": round(v, 2)} for k, v in sorted(agg.items(), key=lambda kv: -kv[1])]


def _donut(holdings: list[dict], top: int = 9) -> list[dict]:
    """상위 top 개 + 나머지 '기타'. 비중이 없는 항목은 제외."""
    rows = [h for h in holdings if (h.get("weight") or 0) > 0]
    head = [{"label": h.get("corp_name") or h.get("etf_name") or h.get("name") or h.get("code", ""), "weight": round(h["weight"], 2)} for h in rows[:top]]
    rest = sum(h["weight"] for h in rows[top:])
    if rest > 0.005:
        head.append({"label": f"기타 {len(rows) - top}종목", "weight": round(rest, 2)})
    return head


async def get_portfolio(code: str) -> dict:
    code = etf_service.check_code(code)
    pdf = await get_pdf(code)
    if not pdf:
        raise ApiError(404, "ETF_PDF_UNAVAILABLE", "구성종목(PDF)을 불러오지 못했습니다. KRX·네이버 모두 응답이 없습니다.")
    holdings = xp.match_holdings(pdf["holdings"], await matcher())
    total_w = round(sum((h.get("weight") or 0) for h in holdings), 2)
    unmatched = sum(1 for h in holdings if h["kind"] == "UNMATCHED")
    return {
        "code": code,
        "date": pdf.get("date", ""),
        "source": pdf.get("source", ""),
        "partial": bool(pdf.get("partial")),
        "count": len(holdings),
        "total_weight": total_w,
        "unmatched_count": unmatched,
        "by_kind": _by_kind(holdings),
        "donut": _donut(holdings),
        "holdings": [_public_holding(h) for h in holdings],
        "note": ("비중은 거래소 PDF 의 구성비중(%)입니다. " if not pdf.get("partial") else "상위 구성종목만 받았습니다(전체 PDF 아님). ")
        + "매칭: 코드(단축코드·ISIN) → 우선주 → 종목명 순으로 DART 상장사·ETF 와 대조하고, 못 찾으면 '미매칭'으로 표시합니다.",
    }


async def _edges_for_cycles(start: str, compact_store: dict[str, dict]) -> dict[str, list[str]]:
    """start 부터 ETF 편입 관계를 따라가며 간선을 만든다. 저장소에 없는 하위 ETF 는 최대 MAX_CYCLE_FETCH 개 새로 받는다."""
    m = await matcher()
    edges: dict[str, list[str]] = {}
    queue, seen, fetched = [(start, 0)], {start}, 0
    while queue:
        node, depth = queue.pop(0)
        c = compact_store.get(node)
        kids: list[str] = c["etfs"] if c else []
        if c is None and fetched < MAX_CYCLE_FETCH:
            fetched += 1
            pdf = await get_pdf(node, allow_stale=True)
            kids = xp.compact(xp.match_holdings(pdf["holdings"], m))["etfs"] if pdf else []
        edges[node] = kids
        if depth < 3:
            for k in kids:
                if k not in seen:
                    seen.add(k)
                    queue.append((k, depth + 1))
    return edges


async def get_relations(code: str) -> dict:
    code = etf_service.check_code(code)
    pdf = await get_pdf(code)
    if not pdf:
        raise ApiError(404, "ETF_PDF_UNAVAILABLE", "구성종목(PDF)을 불러오지 못해 관련성을 계산할 수 없습니다.")
    m = await matcher()
    names = await etf_names()
    lookup = await manager_lookup_fn()
    holdings = xp.match_holdings(pdf["holdings"], m)
    my_manager = lookup(code, names.get(code, ""))
    etf_part = xp.etf_holdings_summary(holdings, my_manager, lookup)

    edges = await _edges_for_cycles(code, await matched_store())
    matched = await matched_store()  # 순환 탐색 중 새로 받은 하위 ETF 까지 포함해 다시
    cycles = xp.find_cycles(code, edges)
    named = lambda c: {"code": c, "name": names.get(c, c)}  # noqa: E731
    holders = [{**h, "name": names.get(h["code"], h["code"]), "manager": lookup(h["code"]), "own": bool(my_manager) and xp.manager_key(lookup(h["code"])) == xp.manager_key(my_manager)}
               for h in xp.holders_of("E:" + code, matched)]
    similar = [{**s, "name": names.get(s["code"], s["code"]), "manager": lookup(s["code"])} for s in xp.most_similar(code, matched)]
    mutual = sorted({h["code"] for h in holders} & set(edges.get(code, [])))
    return {
        "code": code,
        "manager": my_manager,
        "etf_holdings": etf_part,
        "cycles": [[named(c) for c in path] for path in cycles],
        "mutual": [named(c) for c in mutual],
        "held_by": holders,
        "similar": similar,
        "coverage": status(),
        "partial": bool(pdf.get("partial")),
        "note": "자사 ETF = 이 ETF 와 운용사가 같은 ETF. 순환투자 = 편입 ETF 를 따라가다 이 ETF 로 되돌아오는 경로(최대 4단계). "
                "'이 ETF를 담은 ETF'·'구성이 비슷한 ETF'는 서버가 저장한 ETF 구성종목 범위 안에서 계산합니다. "
                "중복도 = 두 ETF 에서 같은 종목 비중의 작은 쪽을 모두 더한 값(현금·파생 제외).",
    }


async def get_overlap(a: str, b: str) -> dict:
    a, b = etf_service.check_code(a), etf_service.check_code(b)
    if a == b:
        raise ApiError(400, "SAME_ETF", "서로 다른 두 ETF 를 고르세요.")
    pa, pb = await asyncio.gather(get_pdf(a), get_pdf(b))
    if not pa or not pb:
        raise ApiError(404, "ETF_PDF_UNAVAILABLE", "두 ETF 중 구성종목을 불러오지 못한 것이 있습니다.")
    m = await matcher()
    names = await etf_names()
    ov = xp.overlap(xp.match_holdings(pa["holdings"], m), xp.match_holdings(pb["holdings"], m))
    return {
        "a": {"code": a, "name": names.get(a, a), "date": pa.get("date", ""), "partial": bool(pa.get("partial"))},
        "b": {"code": b, "name": names.get(b, b), "date": pb.get("date", ""), "partial": bool(pb.get("partial"))},
        **ov,
        "note": "중복도 = 공통 종목마다 두 ETF 비중 중 작은 값을 더한 것(0~100%). 현금·파생은 제외합니다.",
    }


def status() -> dict:
    done, total = store.crawl_progress
    return {"stored": len(store.items), "crawled_on": store.crawled_on, "crawling": store.crawling, "progress": {"done": done, "total": total}}


# ───────────────────────── 하루 한 번 전체 수집 ─────────────────────────

async def crawl_all() -> None:
    if store.crawling:
        return
    store.crawling = True
    try:
        codes = sorted(set((await krx_basic()).keys()) | set((await etf_names()).keys()))
        store.crawl_progress = (0, len(codes))
        ok = 0
        for i, c in enumerate(codes, 1):
            if not store.fresh(c):
                try:
                    if await get_pdf(c):
                        ok += 1
                except Exception as e:  # noqa: BLE001
                    log.info("ETF crawl %s: %s", c, e)
                await asyncio.sleep(CRAWL_DELAY)
            else:
                ok += 1
            store.crawl_progress = (i, len(codes))
            if store._dirty >= 50:
                store.save()
        if codes and ok >= len(codes) * 0.5:
            store.crawled_on = _today_kst().isoformat()
        store.save()
        log.info("ETF PDF crawl finished: %d/%d", ok, len(codes))
    finally:
        store.crawling = False


def _crawl_due(now: datetime) -> bool:
    return now.weekday() < 5 and now.hour >= 8 and store.crawled_on != now.date().isoformat()


async def refresh_loop() -> None:
    store.load()
    await asyncio.sleep(90)  # 서버 시작 직후 다른 초기화와 겹치지 않게
    while True:
        try:
            if _crawl_due(datetime.now(KST)) or not store.items:
                await crawl_all()
        except Exception:  # noqa: BLE001
            log.exception("ETF PDF crawl failed")
        await asyncio.sleep(1800)
