"""공용 httpx 클라이언트. 앱 시작 시 만들고 종료 시 닫는다."""
from __future__ import annotations

import httpx

from .config import get_settings

_client: httpx.AsyncClient | None = None


def get_client() -> httpx.AsyncClient:
    global _client
    if _client is None:
        s = get_settings()
        _client = httpx.AsyncClient(
            timeout=s.http_timeout,
            follow_redirects=True,
            headers={"User-Agent": s.user_agent, "Accept-Language": "ko-KR,ko;q=0.9"},
            limits=httpx.Limits(max_connections=50, max_keepalive_connections=20),
        )
    return _client


async def close_client() -> None:
    global _client
    if _client is not None:
        await _client.aclose()
        _client = None
