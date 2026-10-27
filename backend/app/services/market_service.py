"""시장 구분(유가증권/코스닥/코넥스/외감/기타).

- 상장사: KIND 상장법인목록(corpList.do?method=download)의 '시장구분' 열 → 종목코드별 시장. 하루 한 번 갱신하고 디스크에 저장.
  KIND를 못 읽으면 기업개황(company.json)의 corp_cls(Y/K/N)로 건별 보완한다.
- 비상장: 기본 '외감'. 이름이 유동화전문회사·펀드·조합 등 비외감 성격이면 '기타'.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import time

from bs4 import BeautifulSoup

from ..config import get_settings
from ..http import get_client
from . import dart_client
from .corp_index import CorpEntry

log = logging.getLogger(__name__)

KIND_LIST_URL = "https://kind.krx.co.kr/corpgeneral/corpList.do"
REFRESH_SECONDS = 24 * 3600

MARKET_BADGE = {"KOSPI": "유가증권", "KOSDAQ": "코스닥", "KONEX": "코넥스", "EXTERNAL_AUDIT": "외감", "OTHER": "기타"}
_CLS_TO_MARKET = {"Y": "KOSPI", "K": "KOSDAQ", "N": "KONEX"}
_OTHER_NAME_RE = re.compile(r"유동화|제\d+차|펀드|투자신탁|투자조합|사모|리츠|선박투자|조합$|신탁$|SPC")

_market: dict[str, str] = {}      # 종목코드 → KOSPI|KOSDAQ|KONEX
_loaded_at: float = 0.0
_not_listed: set[str] = set()     # 종목코드는 있으나 기업개황(corp_cls)상 비상장인 코드(예: 코리아센터)


def _path() -> str:
    base = os.path.dirname(get_settings().corp_index_path) or "."
    return os.path.join(base, "market_map.json")


def parse_kind_corp_list(content: bytes) -> dict[str, str]:
    """KIND 상장법인목록(HTML 표, EUC-KR) → {종목코드: 시장}."""
    text = None
    for enc in ("euc-kr", "cp949", "utf-8"):
        try:
            text = content.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    if text is None:
        return {}
    soup = BeautifulSoup(text, "html.parser")
    out: dict[str, str] = {}
    market_idx = code_idx = None
    for tr in soup.find_all("tr"):
        cells = [c.get_text(" ", strip=True) for c in tr.find_all(["th", "td"])]
        if not cells:
            continue
        if market_idx is None and any("시장구분" in c for c in cells):
            market_idx = next(i for i, c in enumerate(cells) if "시장구분" in c)
            code_idx = next((i for i, c in enumerate(cells) if "종목코드" in c), None)
            continue
        if market_idx is None or code_idx is None or len(cells) <= max(market_idx, code_idx):
            continue
        code = re.sub(r"\D", "", cells[code_idx]).zfill(6)
        mk = cells[market_idx]
        if len(code) != 6:
            continue
        out[code] = "KOSDAQ" if "코스닥" in mk else "KONEX" if "코넥스" in mk else "KOSPI"
    return out


async def refresh_from_kind() -> int:
    r = await get_client().get(KIND_LIST_URL, params={"method": "download", "searchType": "13"}, timeout=30)
    if r.status_code != 200:
        raise RuntimeError(f"KIND 상장법인목록 HTTP {r.status_code}")
    data = parse_kind_corp_list(r.content)
    if len(data) < 1000:
        raise RuntimeError(f"KIND 상장법인목록 항목 수 비정상: {len(data)}")
    global _market, _loaded_at
    _market, _loaded_at = data, time.time()
    try:
        with open(_path(), "w", encoding="utf-8") as f:
            json.dump({"saved_at": _loaded_at, "market": data}, f, ensure_ascii=False)
    except OSError:
        log.warning("market map save failed")
    return len(data)


def _save() -> None:
    try:
        with open(_path(), "w", encoding="utf-8") as f:
            json.dump({"saved_at": _loaded_at, "market": _market}, f, ensure_ascii=False)
    except OSError:
        log.warning("market map save failed")


async def ensure_markets(entries: list[CorpEntry]) -> None:
    """KIND 목록에 없는(또는 KIND가 서버 IP를 막은) 상장사는 DART 기업개황의 corp_cls(Y/K/N)로 채우고 저장한다."""
    missing = [e for e in entries if e.is_listed and e.stock_code not in _market]
    if not missing:
        return
    sem = asyncio.Semaphore(5)

    async def one(e: CorpEntry) -> None:
        async with sem:
            try:
                data = await dart_client.get_json("company.json", corp_code=e.corp_code)
            except Exception:  # noqa: BLE001
                return
            remember(e.stock_code, data.get("corp_cls", ""))

    await asyncio.gather(*(one(e) for e in missing))
    _save()


def load_from_disk() -> None:
    global _market, _loaded_at
    try:
        with open(_path(), encoding="utf-8") as f:
            raw = json.load(f)
        _market, _loaded_at = raw["market"], raw.get("saved_at", 0.0)
    except (OSError, ValueError, KeyError):
        pass


async def refresh_loop() -> None:
    load_from_disk()
    while True:
        if time.time() - _loaded_at > REFRESH_SECONDS:
            try:
                n = await refresh_from_kind()
                log.info("market map refreshed: %d", n)
            except Exception as e:  # noqa: BLE001
                log.warning("market map refresh failed: %s", e)
        await asyncio.sleep(3600)


def remember(stock_code: str, corp_cls: str) -> None:
    """기업개황의 corp_cls로 보완(KIND 목록에 없을 때)."""
    m = _CLS_TO_MARKET.get(corp_cls)
    if m and stock_code:
        _market.setdefault(stock_code, m)
        _not_listed.discard(stock_code)
    elif stock_code and corp_cls and stock_code not in _market:
        _not_listed.add(stock_code)  # 종목코드가 있어도 개황상 상장사가 아니면 '외감'으로 본다


def _listed(entry: CorpEntry) -> bool:
    return entry.is_listed and entry.stock_code not in _not_listed


def market_of(entry: CorpEntry) -> str:
    if _listed(entry):
        return _market.get(entry.stock_code, "LISTED")
    return "OTHER" if _OTHER_NAME_RE.search(entry.corp_name) else "EXTERNAL_AUDIT"


def badge_of(entry: CorpEntry) -> str:
    if _listed(entry) and entry.stock_code not in _market:
        return "상장"   # 시장 정보를 아직 못 받은 경우
    return MARKET_BADGE[market_of(entry)]


def badge_from_cls(corp_cls: str, listed: bool, corp_name: str) -> str:
    """기업개황의 corp_cls(Y/K/N/E)로 만든 배지. 비상장은 이름으로 외감/기타 추정."""
    if listed and corp_cls in _CLS_TO_MARKET:
        return MARKET_BADGE[_CLS_TO_MARKET[corp_cls]]
    return MARKET_BADGE["OTHER" if _OTHER_NAME_RE.search(corp_name) else "EXTERNAL_AUDIT"]
