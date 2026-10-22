"""EveryCorp 백엔드 진입점.  실행: uvicorn app.main:app --port 8000"""
from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.gzip import GZipMiddleware

from .config import APP_NAME
from .errors import ApiError, api_error_handler
from .http import close_client
from .routers.api import public, router
from .services import corp_index, market_service

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")


@asynccontextmanager
async def lifespan(_: FastAPI):
    await corp_index.ensure_loaded()
    task = asyncio.create_task(corp_index.refresh_loop())
    market_task = asyncio.create_task(market_service.refresh_loop())
    yield
    task.cancel()
    market_task.cancel()
    await close_client()


def create_app(start_background: bool = True) -> FastAPI:
    app = FastAPI(title=f"{APP_NAME} API", version="1.0.0", lifespan=lifespan if start_background else None)
    app.add_middleware(GZipMiddleware, minimum_size=1024)
    app.add_exception_handler(ApiError, api_error_handler)
    app.include_router(public)
    app.include_router(router)
    return app


app = create_app()
