"""API 오류 형식: {"error": {"code": "...", "message": "..."}}"""
import logging

from fastapi import Request
from fastapi.responses import JSONResponse


log = logging.getLogger(__name__)


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str) -> None:
        self.status = status
        self.code = code
        self.message = message


class UpstreamError(ApiError):
    """DART·KIND·시세 서버 등 외부 원천 오류."""

    def __init__(self, source: str, message: str) -> None:
        super().__init__(502, "UPSTREAM_ERROR", f"[{source}] {message}")


async def api_error_handler(request: Request, exc: ApiError) -> JSONResponse:
    if exc.status >= 500:
        log.warning("%s %s -> %s %s", request.method, request.url.path, exc.status, exc.message)
    return JSONResponse(status_code=exc.status, content={"error": {"code": exc.code, "message": exc.message}})
