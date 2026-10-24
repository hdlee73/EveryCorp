"""지분 변동: 대량보유(majorstock.json)·임원ㆍ주요주주 소유(elestock.json). 회사 단위 조회라 6시간 캐시한다."""
from __future__ import annotations

import asyncio
import logging

from ..cache import cached
from ..parsers import stake as sp
from . import dart_client

log = logging.getLogger(__name__)

LIMIT = 30
SOURCE_NOTE = "출처: DART 지분공시(주식등의 대량보유상황보고서, 임원ㆍ주요주주 특정증권등 소유상황보고서). 최근 보고 순."


async def _rows(path: str, corp_code: str) -> tuple[list[dict], str | None]:
    try:
        data = await dart_client.get_json(path, corp_code=corp_code)
        return data.get("list", []), None
    except dart_client.DartNoData:
        return [], None
    except Exception as e:  # noqa: BLE001  한쪽이 실패해도 다른 쪽은 내려준다
        log.warning("%s %s failed: %s", path, corp_code, e)
        return [], str(e)


@cached(ttl=6 * 3600)
async def get_stake_changes(corp_code: str) -> dict:
    (major, e1), (insider, e2) = await asyncio.gather(_rows("majorstock.json", corp_code), _rows("elestock.json", corp_code))
    return {
        "corp_code": corp_code,
        "major": sp.parse_major(major)[:LIMIT],
        "insiders": sp.parse_insider(insider)[:LIMIT],
        "errors": [e for e in (e1, e2) if e],
        "source": SOURCE_NOTE,
    }
