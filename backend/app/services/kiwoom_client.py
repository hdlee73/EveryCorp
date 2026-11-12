"""키움증권 REST API (openapi.kiwoom.com) 시세 클라이언트.

근거(공개 해설 자료 기준, 공식 명세로 최종 확인 필요 — docs/SETUP_CHECKLIST.md):
- 토큰   POST {base}/oauth2/token  body {grant_type:"client_credentials", appkey, secretkey} → {token, expires_dt, return_code, return_msg}
- 현재가 POST {base}/api/dostk/stkinfo  헤더 api-id: ka10001, authorization: Bearer {token}, body {stk_cd}
- 일봉   POST {base}/api/dostk/chart    헤더 api-id: ka10081, body {stk_cd, base_dt, upd_stkpc_tp}, 연속조회 헤더 cont-yn / next-key
- 업무 오류도 HTTP 200 으로 오며 body 의 return_code != 0 이다. 토큰 만료는 return_code 3 또는 메시지에 8005.
- 토큰을 발급한 IP 와 요청 IP 가 다르면 8010 → 서버는 키움에 등록한 고정 공인 IP 에서만 호출해야 한다.

조회 전용: [ALLOWED_API_IDS]에 있는 시세·차트·수급·랭킹 TR 만 호출한다. 계좌·잔고·주문(kt*, 계좌 ka 계열) TR 은
코드에서 호출할 수 없게 막아 개인 계좌 정보가 서버를 지나가지 않게 한다.
호출 제한: 키움은 초당 호출 수를 넘기면 HTTP 429 또는 return_code 5(1700 "허용된 요청 개수 초과")를 준다.
요청 사이 최소 간격을 두고, 한도 초과면 잠시 쉬었다가 최대 2번 다시 시도한다.
"""
from __future__ import annotations

import asyncio
import logging
import time
from datetime import date, datetime, timedelta, timezone

from ..config import get_settings
from ..errors import ApiError, UpstreamError
from ..http import get_client
from ..parsers import kiwoom_market as km
from ..parsers import price as pp

log = logging.getLogger(__name__)
KST = timezone(timedelta(hours=9))


def kiwoom_enabled() -> bool:
    s = get_settings()
    return bool(s.kiwoom_app_key and s.kiwoom_secret_key)


def _is_token_error(body: dict) -> bool:
    msg = str(body.get("return_msg", ""))
    return str(body.get("return_code")) == "3" or "8005" in msg or "8001" in msg


def _check(body: dict, what: str) -> None:
    if str(body.get("return_code", "0")) != "0":
        raise UpstreamError("키움", f"{what} 실패: {body.get('return_msg', '')}")


class KiwoomToken:
    def __init__(self) -> None:
        self.token: str | None = None
        self.expires_at = 0.0
        self._lock = asyncio.Lock()

    def invalidate(self) -> None:
        self.token = None

    async def get(self) -> str:
        async with self._lock:
            if self.token and time.time() < self.expires_at - 300:
                return self.token
            s = get_settings()
            r = await get_client().post(
                f"{s.kiwoom_base_url}/oauth2/token",
                json={"grant_type": "client_credentials", "appkey": s.kiwoom_app_key, "secretkey": s.kiwoom_secret_key},
                headers={"Content-Type": "application/json;charset=UTF-8"},
            )
            body = r.json() if r.status_code == 200 else {}
            if not body.get("token"):
                raise UpstreamError("키움", f"토큰 발급 실패: {body.get('return_msg') or r.status_code}")
            self.token = body["token"]
            self.expires_at = _parse_expiry(body.get("expires_dt"))
            return self.token


def _parse_expiry(expires_dt: str | None) -> float:
    try:
        return datetime.strptime(str(expires_dt), "%Y%m%d%H%M%S").replace(tzinfo=KST).timestamp()
    except ValueError:
        return time.time() + 23 * 3600


kiwoom_token = KiwoomToken()
_SEM = asyncio.Semaphore(2)  # 키움은 api-id 별 초당 호출 제한이 있어 동시 요청을 줄인다

# 조회 전용 허용 목록(시세·차트·수급·랭킹·ETF). 여기에 없는 TR(계좌·주문 등)은 호출하지 않는다.
ALLOWED_API_IDS = frozenset({
    "ka10001",  # 주식기본정보(현재가)
    "ka10004",  # 주식호가
    "ka10013",  # 신용매매동향
    "ka10014",  # 공매도추이
    "ka10027",  # 전일대비등락률상위
    "ka10030",  # 당일거래량상위
    "ka10059",  # 종목별투자자기관별
    "ka10080", "ka10081", "ka10082", "ka10083",  # 분·일·주·월봉
    "ka90013",  # 종목일별프로그램매매추이
    "ka40001", "ka40002", "ka40003", "ka40004", "ka40006",  # ETF 시세
})


class RateLimiter:
    """요청 시작 시각 사이에 최소 간격을 둔다(전역). 키움 공개 한도보다 보수적으로 초당 약 4건."""

    def __init__(self, min_interval: float = 0.25) -> None:
        self.min_interval = min_interval
        self._next = 0.0
        self._lock = asyncio.Lock()

    async def wait(self) -> None:
        async with self._lock:
            now = time.monotonic()
            delay = self._next - now
            if delay > 0:
                await asyncio.sleep(delay)
            self._next = max(now, self._next) + self.min_interval

    def penalize(self, seconds: float) -> None:
        """한도 초과 응답을 받으면 다음 요청들을 함께 늦춘다."""
        self._next = max(self._next, time.monotonic() + seconds)


rate_limiter = RateLimiter()


def _is_rate_limited(body: dict) -> bool:
    msg = str(body.get("return_msg", ""))
    return str(body.get("return_code")) == "5" or "1700" in msg or "허용된 요청" in msg


class KiwoomBlocked(ApiError):
    def __init__(self, api_id: str) -> None:
        super().__init__(403, "KIWOOM_TR_BLOCKED", f"조회 전용 앱이라 {api_id} TR 은 호출하지 않습니다.")


async def _post(path: str, api_id: str, body: dict, cont_key: str | None = None) -> tuple[dict, dict]:
    """(응답 JSON, 응답 헤더). 토큰이 만료됐으면 한 번 재발급, 호출 한도 초과면 쉬었다가 최대 2번 다시 시도."""
    if api_id not in ALLOWED_API_IDS:
        raise KiwoomBlocked(api_id)
    s = get_settings()
    token_retry, limit_retry = 1, 2
    while True:
        token = await kiwoom_token.get()
        headers = {"Content-Type": "application/json;charset=UTF-8", "authorization": f"Bearer {token}", "api-id": api_id}
        if cont_key:
            headers.update({"cont-yn": "Y", "next-key": cont_key})
        await rate_limiter.wait()
        async with _SEM:
            r = await get_client().post(f"{s.kiwoom_base_url}{path}", json=body, headers=headers)
        data = r.json() if r.status_code == 200 else {}
        if r.status_code == 429 or _is_rate_limited(data):
            if limit_retry <= 0:
                raise UpstreamError("키움", "호출 한도를 초과했습니다. 잠시 후 다시 시도해 주세요.")
            wait = 1.0 if limit_retry == 2 else 2.5
            limit_retry -= 1
            rate_limiter.penalize(wait)
            log.info("Kiwoom rate limited (%s), retry in %.1fs", api_id, wait)
            continue
        if r.status_code != 200:
            raise UpstreamError("키움", f"HTTP {r.status_code}")
        if token_retry > 0 and _is_token_error(data):
            token_retry -= 1
            kiwoom_token.invalidate()
            continue
        return data, dict(r.headers)


async def quote(stock_code: str) -> dict:
    data, _ = await _post("/api/dostk/stkinfo", "ka10001", {"stk_cd": stock_code})
    _check(data, "현재가 조회")
    q = pp.parse_kiwoom_quote(data)
    if not q.get("price"):
        raise UpstreamError("키움", "현재가 응답을 해석하지 못했습니다.")
    return q


async def daily(stock_code: str, bgn: date, max_pages: int = 8) -> list[dict]:
    """수정주가 일봉. base_dt 를 오늘로 두고 연속조회로 과거로 거슬러 올라간다(키움 해설: 권리락 이전 구간도 수정됨)."""
    rows: list[dict] = []
    cont_key: str | None = None
    for _ in range(max_pages):
        data, headers = await _post(
            "/api/dostk/chart", "ka10081",
            {"stk_cd": stock_code, "base_dt": date.today().strftime("%Y%m%d"), "upd_stkpc_tp": "1"},
            cont_key,
        )
        _check(data, "일봉 조회")
        page = pp.parse_kiwoom_chart(data.get("stk_dt_pole_chart_qry") or [])
        rows.extend(page)
        oldest = page[0]["date"] if page else None
        cont_key = headers.get("next-key") or headers.get("Next-Key")
        more = str(headers.get("cont-yn") or headers.get("Cont-Yn") or "N").upper() == "Y"
        if not more or not cont_key or oldest is None or oldest <= bgn.isoformat():
            break
        await asyncio.sleep(0.3)
    uniq = {r["date"]: r for r in rows if r["date"] >= bgn.isoformat()}
    return sorted(uniq.values(), key=lambda r: r["date"])


async def minutes(stock_code: str) -> list[dict]:
    """1분봉(가장 최근 한 페이지). 응답은 최신순이라 파서가 오름차순으로 정렬한다."""
    data, _ = await _post("/api/dostk/chart", "ka10080", {"stk_cd": stock_code, "tic_scope": "1", "upd_stkpc_tp": "1"})
    _check(data, "분봉 조회")
    return pp.parse_kiwoom_minutes(data.get("stk_min_pole_chart_qry") or [])


async def etf_list_page(cont_key: str | None = None) -> tuple[list[dict], str | None]:
    """ka40004 ETF 전체시세 한 페이지(100건)와 다음 페이지 키."""
    data, headers = await _post(
        "/api/dostk/etf", "ka40004",
        {"txon_type": "0", "navpre": "0", "mngmcomp": "0000", "txon_yn": "0", "trace_idex": "0", "stex_tp": "1"},
        cont_key,
    )
    _check(data, "ETF 목록 조회")
    more = str(headers.get("cont-yn") or headers.get("Cont-Yn") or "N").upper() == "Y"
    nxt = (headers.get("next-key") or headers.get("Next-Key")) if more else None
    return data.get("etfall_mrpr") or [], nxt or None


async def etf_info(stock_code: str) -> dict:
    data, _ = await _post("/api/dostk/etf", "ka40002", {"stk_cd": stock_code})
    _check(data, "ETF 종목정보 조회")
    return data


async def etf_daily(stock_code: str) -> list[dict]:
    data, _ = await _post("/api/dostk/etf", "ka40003", {"stk_cd": stock_code})
    _check(data, "ETF 일별추이 조회")
    return data.get("etfdaly_trnsn") or []


# ───────────────────────── 시장 데이터(조회 전용) ─────────────────────────

def _next_key(headers: dict) -> str | None:
    more = str(headers.get("cont-yn") or headers.get("Cont-Yn") or "N").upper() == "Y"
    key = headers.get("next-key") or headers.get("Next-Key")
    return key if more and key else None


CHART_API = {"m": "ka10080", "D": "ka10081", "W": "ka10082", "M": "ka10083"}


async def candles(stock_code: str, period: str, scope: int = 1, count: int = 240, max_pages: int = 6) -> list[dict]:
    """period: m(분봉, scope=1/3/5/10/15/30/45/60) | D | W | M. 수정주가, 최근 [count]개(오름차순)."""
    api_id = CHART_API[period]
    body: dict = {"stk_cd": stock_code, "upd_stkpc_tp": "1"}
    if period == "m":
        body["tic_scope"] = str(scope)
    else:
        body["base_dt"] = date.today().strftime("%Y%m%d")
    rows: dict[str, dict] = {}
    cont: str | None = None
    for _ in range(max_pages):
        data, headers = await _post("/api/dostk/chart", api_id, body, cont)
        _check(data, "차트 조회")
        page = km.parse_candles(api_id, data)
        for r in page:
            rows[r["t"]] = r
        cont = _next_key(headers)
        if not page or not cont or len(rows) >= count:
            break
    return sorted(rows.values(), key=lambda r: r["t"])[-count:]


async def orderbook(stock_code: str) -> dict:
    data, _ = await _post("/api/dostk/mrkcond", "ka10004", {"stk_cd": stock_code})
    _check(data, "호가 조회")
    return km.parse_orderbook(data)


async def investors(stock_code: str) -> list[dict]:
    """투자자별 일별 순매수 수량(주). trde_tp 0=순매수, amt_qty_tp 2=수량, unit_tp 1=단주."""
    data, _ = await _post("/api/dostk/stkinfo", "ka10059", {
        "dt": date.today().strftime("%Y%m%d"), "stk_cd": stock_code, "amt_qty_tp": "2", "trde_tp": "0", "unit_tp": "1",
    })
    _check(data, "투자자별 매매 조회")
    return km.parse_investors(data)


async def program(stock_code: str) -> list[dict]:
    data, _ = await _post("/api/dostk/mrkcond", "ka90013", {
        "amt_qty_tp": "1", "stk_cd": stock_code, "date": date.today().strftime("%Y%m%d"),
    })
    _check(data, "프로그램 매매 조회")
    return km.parse_program(data)


async def short_selling(stock_code: str, days: int = 60) -> list[dict]:
    end = date.today()
    data, _ = await _post("/api/dostk/shsa", "ka10014", {
        "stk_cd": stock_code, "tm_tp": "1",
        "strt_dt": (end - timedelta(days=days)).strftime("%Y%m%d"), "end_dt": end.strftime("%Y%m%d"),
    })
    _check(data, "공매도 조회")
    return km.parse_short(data)


async def credit(stock_code: str) -> list[dict]:
    """신용융자(qry_tp 1) 일별 추이."""
    data, _ = await _post("/api/dostk/stkinfo", "ka10013", {
        "stk_cd": stock_code, "dt": date.today().strftime("%Y%m%d"), "qry_tp": "1",
    })
    _check(data, "신용매매 조회")
    return km.parse_credit(data)


MARKET_TP = {"all": "000", "kospi": "001", "kosdaq": "101"}


async def ranking(kind: str, market: str = "all") -> list[dict]:
    """kind: volume(거래량) | value(거래대금) | rise(상승률) | fall(하락률). 관리종목·ETF/ETN 은 제외한다."""
    mrkt = MARKET_TP.get(market, "000")
    if kind in ("volume", "value"):
        api_id, body = "ka10030", {
            "mrkt_tp": mrkt, "sort_tp": "1" if kind == "volume" else "3", "mang_stk_incls": "16", "crd_tp": "0",
            "trde_qty_tp": "0", "pric_tp": "0", "trde_prica_tp": "0", "mrkt_open_tp": "0", "stex_tp": "3",
        }
    else:
        api_id, body = "ka10027", {
            "mrkt_tp": mrkt, "sort_tp": "1" if kind == "rise" else "3", "trde_qty_cnd": "0000", "stk_cnd": "16",
            "crd_cnd": "0", "updown_incls": "1", "pric_cnd": "0", "trde_prica_cnd": "0", "stex_tp": "3",
        }
    data, _ = await _post("/api/dostk/rkinfo", api_id, body)
    _check(data, "순위 조회")
    return km.parse_ranking(data)
