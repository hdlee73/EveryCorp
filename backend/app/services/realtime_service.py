"""실시간 시세·호가 중계(키움 WebSocket → 앱 WebSocket).

- 키움 WebSocket 연결은 서버에 하나만 둔다(같은 앱키로 여러 연결을 열지 않음). 앱 연결이 여럿이어도
  종목별 참조 수를 세어 REG/REMOVE 를 보낸다. 마지막 앱이 나가면 키움 연결을 닫는다.
- 실시간 유형은 시세(0B 주식체결)·호가(0D 주식호가잔량)만 등록한다. 주문체결(00)·잔고(04) 같은 계좌 유형은 등록하지 않는다.
- 키움은 PING 을 보내며 그대로 돌려보내야 연결을 유지한다. 끊기면 지수 백오프(1→30초)로 다시 LOGIN·REG 한다.
- 키움 키가 없으면(또는 키움 연결이 계속 실패하면) 3초 간격 현재가 폴링(네이버 등)으로 'trade' 이벤트만 보낸다.

근거: 키움 WebSocket wss://api.kiwoom.com:10000/api/dostk/websocket, LOGIN {"trnm":"LOGIN","token"},
REG {"trnm":"REG","grp_no","refresh","data":[{"item":[..],"type":[..]}]}, REMOVE 동일(refresh 없음), REAL 응답 data[].values(FID).
"""
from __future__ import annotations

import asyncio
import json
import logging
import time
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable

from ..config import get_settings
from ..parsers import kiwoom_market as km
from .kiwoom_client import kiwoom_enabled, kiwoom_token

log = logging.getLogger(__name__)

REAL_TYPES = ("0B", "0D")           # 시세·호가만(계좌 유형 금지)
MAX_CODES_PER_CLIENT = 5
MAX_ITEMS = 60                      # 서버 전체 동시 등록 종목 수 상한
POLL_SECONDS = 3.0
GRP_NO = "1"


def ws_url() -> str:
    base = get_settings().kiwoom_base_url.rstrip("/")
    host = base.split("://", 1)[-1]
    return f"wss://{host}:10000/api/dostk/websocket"


Send = Callable[[dict], Awaitable[None]]
Connector = Callable[[str], Awaitable[Any]]  # url → 연결 객체(send(str), recv() -> str, close())


@dataclass
class Client:
    send: Send
    codes: set[str] = field(default_factory=set)


async def _default_connect(url: str):
    from websockets.asyncio.client import connect

    # 키움 PING 은 애플리케이션 메시지라 프로토콜 ping 은 끈다.
    return await connect(url, ping_interval=None, open_timeout=10, close_timeout=5, max_size=2 ** 20)


class RealtimeHub:
    def __init__(self, connector: Connector | None = None, quote_loader: Callable[[str], Awaitable[dict]] | None = None):
        self._connector = connector or _default_connect
        self._quote_loader = quote_loader
        self.clients: dict[int, Client] = {}
        self.refcount: dict[str, int] = {}
        self._ws = None
        self._task: asyncio.Task | None = None
        self._poll_task: asyncio.Task | None = None
        self._lock = asyncio.Lock()
        self.mode = "idle"  # idle | kiwoom | polling
        self._last_fail = 0.0
        self._consecutive_fail = 0

    # ───── 앱 쪽 ─────
    async def add_client(self, cid: int, send: Send) -> None:
        self.clients[cid] = Client(send)
        await send({"type": "status", "mode": self.mode if self.mode != "idle" else self._planned_mode()})

    async def remove_client(self, cid: int) -> None:
        c = self.clients.pop(cid, None)
        if c:
            await self._release(c.codes)

    async def subscribe(self, cid: int, codes: list[str]) -> list[str]:
        c = self.clients.get(cid)
        if c is None:
            return []
        clean = [x for x in dict.fromkeys(codes) if len(x) == 6 and x.isalnum()]
        room = MAX_CODES_PER_CLIENT - len(c.codes)
        new = [x for x in clean if x not in c.codes][: max(room, 0)]
        new = [x for x in new if x in self.refcount or len(self.refcount) < MAX_ITEMS]
        if not new:
            return sorted(c.codes)
        c.codes.update(new)
        first = [x for x in new if self.refcount.get(x, 0) == 0]
        for x in new:
            self.refcount[x] = self.refcount.get(x, 0) + 1
        await self._ensure_running()
        if first and self._ws is not None:
            await self._send_upstream({"trnm": "REG", "grp_no": GRP_NO, "refresh": "1",
                                       "data": [{"item": first, "type": list(REAL_TYPES)}]})
        return sorted(c.codes)

    async def unsubscribe(self, cid: int, codes: list[str]) -> list[str]:
        c = self.clients.get(cid)
        if c is None:
            return []
        gone = {x for x in codes if x in c.codes}
        c.codes -= gone
        await self._release(gone)
        return sorted(c.codes)

    async def _release(self, codes: set[str]) -> None:
        last = []
        for x in codes:
            n = self.refcount.get(x, 0) - 1
            if n <= 0:
                self.refcount.pop(x, None)
                last.append(x)
            else:
                self.refcount[x] = n
        if last and self._ws is not None:
            await self._send_upstream({"trnm": "REMOVE", "grp_no": GRP_NO, "data": [{"item": last, "type": list(REAL_TYPES)}]})

    async def broadcast(self, event: dict) -> None:
        code = event.get("code")
        for c in list(self.clients.values()):
            if code is None or code in c.codes:
                try:
                    await c.send(event)
                except Exception:  # noqa: BLE001 — 끊긴 앱은 그 연결의 핸들러가 정리한다
                    pass

    # ───── 키움 쪽 ─────
    def _planned_mode(self) -> str:
        if not kiwoom_enabled():
            return "polling"
        # 키움이 연속 실패하는 중이면 잠시 폴링으로 버틴다
        return "polling" if self._consecutive_fail >= 3 else "kiwoom"

    async def _ensure_running(self) -> None:
        async with self._lock:
            if self._task is None or self._task.done():
                self._task = asyncio.create_task(self._run())

    async def _send_upstream(self, msg: dict) -> None:
        ws = self._ws
        if ws is None:
            return
        try:
            await ws.send(json.dumps(msg))
        except Exception as e:  # noqa: BLE001
            log.warning("kiwoom ws send failed: %s", type(e).__name__)

    async def _run(self) -> None:
        backoff = 1.0
        try:
            while True:
                if not self.refcount:
                    if not self.clients:
                        break
                    await asyncio.sleep(1)
                    continue
                mode = self._planned_mode()
                await self._set_mode(mode)
                if mode == "polling":
                    await self._poll_once()
                    await asyncio.sleep(POLL_SECONDS)
                    if kiwoom_enabled() and time.monotonic() - self._last_fail > 60:
                        self._consecutive_fail = 0  # 1분 뒤 키움 재시도
                    continue
                try:
                    await self._session()
                    backoff = 1.0
                except asyncio.CancelledError:
                    raise
                except Exception as e:  # noqa: BLE001
                    self._consecutive_fail += 1
                    self._last_fail = time.monotonic()
                    log.warning("kiwoom ws session ended: %s (%d)", type(e).__name__, self._consecutive_fail)
                    await asyncio.sleep(backoff)
                    backoff = min(backoff * 2, 30.0)
        finally:
            await self._close_ws()
            self.mode = "idle"
            # 끝나는 사이에 새 구독이 들어왔으면 다시 시작한다(구독이 멈춘 채 남지 않게).
            if self.refcount and self.clients:
                self._task = asyncio.create_task(self._run())

    async def _set_mode(self, mode: str) -> None:
        if mode != self.mode:
            self.mode = mode
            await self.broadcast({"type": "status", "mode": mode})

    async def _close_ws(self) -> None:
        ws, self._ws = self._ws, None
        if ws is not None:
            try:
                await ws.close()
            except Exception:  # noqa: BLE001
                pass

    async def _session(self) -> None:
        ws = await self._connector(ws_url())
        try:
            await ws.send(json.dumps({"trnm": "LOGIN", "token": await kiwoom_token.get()}))
            login = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
            if str(login.get("return_code", "0")) != "0":
                msg = str(login.get("return_msg", ""))
                if "8005" in msg or "토큰" in msg:
                    kiwoom_token.invalidate()
                raise RuntimeError(f"LOGIN 실패 {login.get('return_code')}")
            self._ws = ws
            self._consecutive_fail = 0
            codes = list(self.refcount)
            if codes:
                await self._send_upstream({"trnm": "REG", "grp_no": GRP_NO, "refresh": "1",
                                           "data": [{"item": codes, "type": list(REAL_TYPES)}]})
            while self.clients:
                try:
                    raw = await asyncio.wait_for(ws.recv(), timeout=5)
                except asyncio.TimeoutError:
                    if not self.refcount and not self.clients:
                        break
                    continue
                await self._handle_raw(ws, raw)
                if not self.refcount and not self.clients:
                    break
        finally:
            self._ws = None
            try:
                await ws.close()
            except Exception:  # noqa: BLE001
                pass

    async def _handle_raw(self, ws, raw) -> None:
        try:
            msg = json.loads(raw)
        except (TypeError, ValueError):
            return
        trnm = msg.get("trnm")
        if trnm == "PING":
            await ws.send(raw if isinstance(raw, str) else json.dumps(msg))  # 받은 그대로 돌려보낸다
            return
        if trnm in ("REG", "REMOVE"):
            if str(msg.get("return_code", "0")) != "0":
                log.warning("kiwoom %s failed: %s", trnm, msg.get("return_code"))
            return
        for ev in km.parse_real_message(msg):
            await self.broadcast(ev)

    async def _poll_once(self) -> None:
        if self._quote_loader is None:
            from .price_service import quote_by_code

            async def loader(code: str) -> dict:
                return await quote_by_code(code, "", "")
            self._quote_loader = loader
        for code in list(self.refcount):
            try:
                q = await self._quote_loader(code)
            except Exception:  # noqa: BLE001
                continue
            await self.broadcast({"type": "trade", "code": code, "price": q.get("price"), "change": q.get("change"),
                                  "change_rate": q.get("change_rate"), "direction": q.get("direction"),
                                  "acc_volume": q.get("volume"), "open": q.get("open"), "high": q.get("high"),
                                  "low": q.get("low"), "time": None, "source": q.get("source"), "polled": True})


hub = RealtimeHub()
