"""키움증권 REST API (openapi.kiwoom.com) 시세 클라이언트.

근거(공개 해설 자료 기준, 공식 명세로 최종 확인 필요 — docs/SETUP_CHECKLIST.md):
- 토큰   POST {base}/oauth2/token  body {grant_type:"client_credentials", appkey, secretkey} → {token, expires_dt, return_code, return_msg}
- 현재가 POST {base}/api/dostk/stkinfo  헤더 api-id: ka10001, authorization: Bearer {token}, body {stk_cd}
- 일봉   POST {base}/api/dostk/chart    헤더 api-id: ka10081, body {stk_cd, base_dt, upd_stkpc_tp}, 연속조회 헤더 cont-yn / next-key
- 업무 오류도 HTTP 200 으로 오며 body 의 return_code != 0 이다. 토큰 만료는 return_code 3 또는 메시지에 8005.
- 토큰을 발급한 IP 와 요청 IP 가 다르면 8010 → 서버는 키움에 등록한 고정 공인 IP 에서만 호출해야 한다.
"""
from __future__ import annotations

import asyncio
import logging
import time
from datetime import date, datetime, timedelta, timezone

from ..config import get_settings
from ..errors import ApiError, UpstreamError
from ..http import get_client
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


async def _post(path: str, api_id: str, body: dict, cont_key: str | None = None) -> tuple[dict, dict]:
    """(응답 JSON, 응답 헤더). 토큰이 만료됐으면 한 번 재발급 후 재시도."""
    s = get_settings()
    for attempt in (0, 1):
        token = await kiwoom_token.get()
        headers = {"Content-Type": "application/json;charset=UTF-8", "authorization": f"Bearer {token}", "api-id": api_id}
        if cont_key:
            headers.update({"cont-yn": "Y", "next-key": cont_key})
        async with _SEM:
            r = await get_client().post(f"{s.kiwoom_base_url}{path}", json=body, headers=headers)
        if r.status_code == 429:
            raise UpstreamError("키움", "호출 한도를 초과했습니다.")
        if r.status_code != 200:
            raise UpstreamError("키움", f"HTTP {r.status_code}")
        data = r.json()
        if attempt == 0 and _is_token_error(data):
            kiwoom_token.invalidate()
            continue
        return data, dict(r.headers)
    raise UpstreamError("키움", "토큰을 갱신하지 못했습니다.")


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
