"""DART 고유번호(corp_code) 매핑 관리.

- corpCode.xml(zip)을 받아 회사명·종목코드·고유번호 인덱스를 만들고 디스크에 저장한다.
- 서버 시작 시 디스크 사본을 먼저 읽고(빠른 기동), 24시간마다 새로 받는다.
- 검색: 정규화한 회사명 기준 완전일치 > 앞부분 일치 > 포함, 상장사 우선, 초성 검색(ㅅㅅㅈㅈ) 지원.
  종목코드 6자리 숫자 입력 시 종목코드로 바로 찾는다.
"""
from __future__ import annotations

import asyncio
import io
import json
import logging
import os
import time
import zipfile
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass

from ..config import get_settings
from ..utils.text import is_chosung_query, normalize_name, to_chosung
from . import dart_client

log = logging.getLogger(__name__)
REFRESH_SECONDS = 24 * 3600


@dataclass(slots=True)
class CorpEntry:
    corp_code: str
    corp_name: str
    corp_name_eng: str
    stock_code: str          # 상장사만 6자리, 아니면 ""
    modify_date: str

    @property
    def is_listed(self) -> bool:
        return len(self.stock_code.strip()) == 6


def parse_corp_code_xml(xml_bytes: bytes) -> list[CorpEntry]:
    root = ET.fromstring(xml_bytes)
    out: list[CorpEntry] = []
    for el in root.iter("list"):
        def t(tag: str) -> str:
            return (el.findtext(tag) or "").strip()

        out.append(CorpEntry(t("corp_code"), t("corp_name"), t("corp_eng_name"), t("stock_code"), t("modify_date")))
    return out


def unzip_corp_code(zip_bytes: bytes) -> bytes:
    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as z:
        name = next(n for n in z.namelist() if n.lower().endswith(".xml"))
        return z.read(name)


class CorpIndex:
    def __init__(self) -> None:
        self.entries: list[CorpEntry] = []
        self.by_code: dict[str, CorpEntry] = {}
        self.by_stock: dict[str, CorpEntry] = {}
        self._norm: list[str] = []
        self._cho: list[str] = []
        self.loaded_at: float = 0.0

    # ---------- 적재 ----------
    def load(self, entries: list[CorpEntry]) -> None:
        self.entries = entries
        self.by_code = {e.corp_code: e for e in entries}
        self.by_stock = {e.stock_code: e for e in entries if e.is_listed}
        self._norm = [normalize_name(e.corp_name) for e in entries]
        self._cho = [to_chosung(n) for n in self._norm]
        self.loaded_at = time.time()

    def load_from_disk(self, path: str) -> bool:
        if not os.path.exists(path):
            return False
        with open(path, encoding="utf-8") as f:
            raw = json.load(f)
        self.load([CorpEntry(**r) for r in raw["entries"]])
        self.loaded_at = raw.get("saved_at", time.time())
        return True

    def save_to_disk(self, path: str) -> None:
        os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({"saved_at": time.time(), "entries": [asdict(e) for e in self.entries]}, f, ensure_ascii=False)
        os.replace(tmp, path)

    async def refresh_from_dart(self) -> None:
        zip_bytes = await dart_client.get_bytes("corpCode.xml")
        entries = parse_corp_code_xml(unzip_corp_code(zip_bytes))
        if len(entries) < 1000:  # 비정상 응답 방어
            raise RuntimeError(f"corpCode.xml 항목 수 비정상: {len(entries)}")
        self.load(entries)
        self.save_to_disk(get_settings().corp_index_path)
        log.info("corp index refreshed: %d entries", len(entries))

    @property
    def stale(self) -> bool:
        return time.time() - self.loaded_at > REFRESH_SECONDS

    # ---------- 조회 ----------
    def get(self, corp_code: str) -> CorpEntry | None:
        return self.by_code.get(corp_code)

    def search(self, query: str, limit: int = 20, listed_only: bool = False) -> list[CorpEntry]:
        q = query.strip()
        if not q:
            return []
        if q.isdigit() and len(q) == 6 and q in self.by_stock:
            return [self.by_stock[q]]
        if q.isdigit() and len(q) == 8 and q in self.by_code:
            return [self.by_code[q]]

        cho = is_chosung_query(q.replace(" ", ""))
        nq = q.replace(" ", "") if cho else normalize_name(q)
        if not nq:
            return []
        haystack = self._cho if cho else self._norm

        scored: list[tuple[tuple, CorpEntry]] = []
        for i, name in enumerate(haystack):
            if nq not in name:
                continue
            e = self.entries[i]
            if listed_only and not e.is_listed:
                continue
            if name == nq:
                rank = 0
            elif name.startswith(nq):
                rank = 1
            else:
                rank = 2
            # 상장사 우선 → 이름 짧은 순 → 최근 변경 순
            scored.append(((rank, 0 if e.is_listed else 1, len(name), -int(e.modify_date or 0)), e))
        scored.sort(key=lambda x: x[0])
        return [e for _, e in scored[:limit]]


corp_index = CorpIndex()


async def ensure_loaded() -> None:
    """시작 시 호출: 디스크 사본 → 없거나 오래되면 DART에서 갱신."""
    path = get_settings().corp_index_path
    try:
        corp_index.load_from_disk(path)
    except Exception:  # noqa: BLE001
        log.exception("corp index disk load failed")
    if not corp_index.entries or corp_index.stale:
        try:
            await corp_index.refresh_from_dart()
        except Exception:  # noqa: BLE001
            log.exception("corp index refresh failed")


async def refresh_loop() -> None:
    while True:
        await asyncio.sleep(3600)
        if corp_index.stale:
            try:
                await corp_index.refresh_from_dart()
            except Exception:  # noqa: BLE001
                log.exception("corp index refresh failed")
