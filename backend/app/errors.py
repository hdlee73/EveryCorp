"""API 오류 형식: {"error": {"code": "...", "message": "..."}}"""
from fastapi import Request
from fastapi.responses import JSONResponse


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str) -> None:
        self.status = status
        self.code = code
        self.message = message


class UpstreamError(ApiError):
    """DART·KIND·시세 서버 등 외부 원천 오류."""

    def __init__(self, source: str, message: str) -> None:
        super().__init__(502, "UPSTREAM_ERROR", f"[{source}] {message}")


async def api_error_handler(_: Request, exc: ApiError) -> JSONResponse:
    return JSONResponse(status_code=exc.status, content={"error": {"code": exc.code, "message": exc.message}})
