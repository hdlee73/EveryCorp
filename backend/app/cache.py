"""가벼운 비동기 TTL 캐시.

- 같은 키를 동시에 요청하면 한 번만 외부 호출한다(in-flight 합치기).
- 예외는 캐시하지 않는다.
- 프로세스 메모리 기반. 인스턴스를 여러 대 띄우면 Redis 등으로 교체한다.
"""
from __future__ import annotations

import asyncio
import functools
import time
from typing import Any, Awaitable, Callable, TypeVar

T = TypeVar("T")


class TTLCache:
    def __init__(self, maxsize: int = 2048) -> None:
        self._data: dict[Any, tuple[float, Any]] = {}
        self._locks: dict[Any, asyncio.Lock] = {}
        self._maxsize = maxsize

    def get(self, key: Any) -> Any | None:
        hit = self._data.get(key)
        if hit is None:
            return None
        expires, value = hit
        if expires < time.monotonic():
            self._data.pop(key, None)
            return None
        return value

    def set(self, key: Any, value: Any, ttl: float) -> None:
        if len(self._data) >= self._maxsize:
            # 가장 먼저 만료되는 항목부터 정리
            for k, _ in sorted(self._data.items(), key=lambda kv: kv[1][0])[: self._maxsize // 10 or 1]:
                self._data.pop(k, None)
        self._data[key] = (time.monotonic() + ttl, value)

    def clear(self) -> None:
        self._data.clear()

    async def get_or_load(self, key: Any, ttl: float, loader: Callable[[], Awaitable[T]]) -> T:
        value = self.get(key)
        if value is not None:
            return value
        lock = self._locks.setdefault(key, asyncio.Lock())
        async with lock:
            value = self.get(key)
            if value is not None:
                return value
            value = await loader()
            if value is not None:
                self.set(key, value, ttl)
            return value


cache = TTLCache()


def cached(ttl: float, prefix: str | None = None):
    """async 함수 결과를 인자 기준으로 캐시하는 데코레이터."""

    def deco(fn: Callable[..., Awaitable[T]]) -> Callable[..., Awaitable[T]]:
        name = prefix or f"{fn.__module__}.{fn.__qualname__}"

        @functools.wraps(fn)
        async def wrapper(*args, **kwargs):
            key = (name, args, tuple(sorted(kwargs.items())))
            return await cache.get_or_load(key, ttl, lambda: fn(*args, **kwargs))

        return wrapper

    return deco
