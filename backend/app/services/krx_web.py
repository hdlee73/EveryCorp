"""KRX 정보데이터시스템(data.krx.co.kr) JSON 호출. 로그인이 필요해진 화면을 위해 회원 로그인 세션을 유지한다.

- KRX 는 정보데이터시스템 자료 조회에 회원 로그인을 요구하도록 바뀌었다(로그인 없이 부르면 HTTP 400 'LOGOUT').
  서버 환경변수 KRX_ID / KRX_PW(KRX 회원 아이디·비밀번호)가 있으면 로그인해 쿠키(JSESSIONID)를 유지한다.
- 로그인 흐름은 pykrx(website/comm/auth.py) 와 같다:
  GET MDCCOMS001.cmd → GET login.jsp → POST MDCCOMS001D1.cmd(mbrId, pw) → CD011(중복 로그인)이면 skipDup=Y 로 다시.
  결과 코드 CD001 이 정상.
- 아이디·비밀번호는 로그에 남기지 않는다.
"""
from __future__ import annotations

import asyncio
import logging
import time

import httpx

from ..config import get_settings
from ..errors import UpstreamError

log = logging.getLogger(__name__)

DATA_URL = "https://data.krx.co.kr/comm/bldAttendant/getJsonData.cmd"
REFERER = "https://data.krx.co.kr/contents/MDC/MDI/mdiLoader/index.cmd?menuId=MDC0201030101"
LOGIN_PAGE = "https://data.krx.co.kr/contents/MDC/COMS/client/MDCCOMS001.cmd"
LOGIN_JSP = "https://data.krx.co.kr/contents/MDC/COMS/client/view/login.jsp?site=mdc"
LOGIN_URL = "https://data.krx.co.kr/contents/MDC/COMS/client/MDCCOMS001D1.cmd"
SESSION_TTL = 50 * 60      # KRX 세션은 약 1시간. 여유를 두고 다시 로그인
LOGIN_RETRY_AFTER = 600    # 로그인 실패 뒤 10분 동안은 다시 시도하지 않는다(계정 잠김 방지)


def has_login() -> bool:
    s = get_settings()
    return bool(s.krx_id and s.krx_pw)


class KrxWeb:
    def __init__(self) -> None:
        self._client: httpx.AsyncClient | None = None
        self.logged_in_at = 0.0
        self.login_failed_at = 0.0
        self.login_error = ""
        self._lock = asyncio.Lock()

    def _http(self) -> httpx.AsyncClient:
        if self._client is None:
            s = get_settings()
            self._client = httpx.AsyncClient(
                timeout=s.http_timeout, follow_redirects=True,
                headers={"User-Agent": s.user_agent, "Accept-Language": "ko-KR,ko;q=0.9"},
            )
        return self._client

    async def close(self) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None

    def status(self) -> dict:
        return {
            "login_configured": has_login(),
            "logged_in": bool(self.logged_in_at) and time.time() - self.logged_in_at < SESSION_TTL,
            "login_error": self.login_error,
        }

    async def _login(self) -> bool:
        s = get_settings()
        c = self._http()
        c.cookies.clear()
        try:
            await c.get(LOGIN_PAGE)
            await c.get(LOGIN_JSP, headers={"Referer": LOGIN_PAGE})
            form = {"mbrNm": "", "telNo": "", "di": "", "certType": "", "mbrId": s.krx_id, "pw": s.krx_pw}
            r = await c.post(LOGIN_URL, data=form, headers={"Referer": LOGIN_PAGE})
            data = r.json()
            code = data.get("_error_code", "")
            if code == "CD011":  # 다른 곳에서 로그인 중 → 그 세션을 끊고 로그인
                r = await c.post(LOGIN_URL, data={**form, "skipDup": "Y"}, headers={"Referer": LOGIN_PAGE})
                data = r.json()
                code = data.get("_error_code", "")
        except Exception as e:  # noqa: BLE001
            code, data = "", {"_error_message": f"{type(e).__name__}: {e}"}
        if code == "CD001":
            self.logged_in_at = time.time()
            self.login_error = ""
            log.info("KRX data login ok")
            return True
        self.logged_in_at = 0.0
        self.login_failed_at = time.time()
        msg = str(data.get("_error_message", "") or "")[:120]
        self.login_error = f"KRX 로그인 실패({code or '응답 없음'}{': ' + msg if msg else ''})" + (" — KRX 사이트에서 비밀번호 변경 필요" if code == "CD010" else "")
        log.warning("KRX data login failed: %s", code or msg)
        return False

    async def _ensure_login(self, force: bool = False) -> None:
        if not has_login():
            return
        async with self._lock:
            fresh = self.logged_in_at and time.time() - self.logged_in_at < SESSION_TTL
            if fresh and not force:
                return
            if time.time() - self.login_failed_at < LOGIN_RETRY_AFTER:
                return
            await self._login()

    async def post_json(self, form: dict) -> dict:
        """정보데이터시스템 JSON. 로그인 요구·차단 시 UpstreamError."""
        await self._ensure_login()
        body = {"locale": "ko_KR", "csvxls_isNo": "false", **form}
        headers = {"Referer": REFERER, "X-Requested-With": "XMLHttpRequest"}
        for attempt in (0, 1):
            r = await self._http().post(DATA_URL, data=body, headers=headers)
            logout = r.status_code == 400 or "LOGOUT" in r.text[:200]
            if logout and attempt == 0 and has_login():
                self.logged_in_at = 0.0
                await self._ensure_login(force=True)
                if self.logged_in_at:
                    continue
            if logout:
                hint = self.login_error if has_login() else "로그인 필요: 서버 .env 에 KRX_ID·KRX_PW(KRX 회원 아이디·비밀번호)를 넣어 주세요"
                raise UpstreamError("KRX", f"정보데이터시스템 HTTP {r.status_code} ({hint})")
            if r.status_code != 200:
                raise UpstreamError("KRX", f"정보데이터시스템 HTTP {r.status_code}")
            try:
                data = r.json()
            except ValueError as e:
                raise UpstreamError("KRX", "정보데이터시스템이 JSON 대신 다른 응답(로그인 요구 등)을 보냈습니다.") from e
            if not isinstance(data, dict):
                raise UpstreamError("KRX", "정보데이터시스템 응답 형식이 다릅니다.")
            return data
        raise UpstreamError("KRX", "정보데이터시스템 응답 없음")


krx_web = KrxWeb()
