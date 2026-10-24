"""스크리닝: 감사의견 비적정 · 자본잠식 상장사 목록.

DART는 회사 한 곳씩(감사의견) 또는 100곳씩(주요계정) 조회해야 해서 요청 때 즉석 계산이 어렵다.
그래서 서버가 백그라운드로 모아 디스크(JSON)에 저장해 두고, 앱은 저장본을 조회한다.
- 감사의견: 해당 결산연도 사업보고서(A001)를 제출한 법인(비상장 포함) 전체를 accnutAdtorNmNdAdtOpinion으로 조회
- 자본잠식: 사업보고서 제출 법인과 상장사를 합쳐 fnlttMultiAcnt(100곳 묶음)로 조회해 자본총계 < 자본금인 회사를 고른다
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import time
from datetime import date

from ..config import get_settings
from . import dart_client
from .corp_index import corp_index
from .reports_service import summarize_opinions

log = logging.getLogger(__name__)

REPRT_NAMES = {"11011": "사업보고서", "11012": "반기보고서", "11013": "1분기보고서", "11014": "3분기보고서"}
_PERIOD_RE = re.compile(r"\((\d{4})\.(\d{2})\)")
_MULTI_BATCH = 100
_AUDIT_CONCURRENCY = 4
_SAVE_EVERY = 200

_jobs: dict[str, asyncio.Task] = {}
_progress: dict[str, tuple[int, int]] = {}


# ───────── 순수 함수 (테스트 대상) ─────────

def is_clean_opinion(opinion: str) -> bool:
    """'적정'(및 '적정의견')만 적정. '부적정'은 '적정'으로 시작하지 않으므로 비적정."""
    return re.sub(r"\s|의견", "", opinion or "").startswith("적정")


def is_flagged_opinion(opinion: str) -> bool:
    o = (opinion or "").strip()
    return o not in ("", "-") and not is_clean_opinion(o)


def _amount(s: object) -> int | None:
    t = str(s or "").replace(",", "").strip()
    if not t or t == "-":
        return None
    try:
        return int(t)
    except ValueError:
        return None


def parse_multi_acnt(rows: list[dict]) -> dict[str, dict]:
    """fnlttMultiAcnt 응답 → {corp_code: {assets, liabilities, equity, capital, fs_div, period}} (연결 우선)."""
    best: dict[str, dict] = {}
    for r in rows:
        if r.get("sj_div") != "BS":
            continue
        cc = r.get("corp_code", "")
        fs = r.get("fs_div", "")
        key = {"자산총계": "assets", "부채총계": "liabilities", "자본총계": "equity", "자본금": "capital"}.get(
            (r.get("account_nm") or "").strip())
        if not cc or not key:
            continue
        cur = best.get(cc)
        if cur is None or (fs == "CFS" and cur["fs_div"] != "CFS"):
            # 별도만 쌓여 있던 회사에 연결이 나오면 통째로 교체
            cur = best[cc] = {"fs_div": fs, "assets": None, "liabilities": None, "equity": None, "capital": None, "period": ""}
        if cur["fs_div"] != fs:
            continue
        cur[key] = _amount(r.get("thstrm_amount"))
        m = re.match(r"(\d{4})\.(\d{2})", r.get("thstrm_dt") or "")
        if m:
            cur["period"] = f"{m.group(1)}-{m.group(2)}"
    return best


def impairment_kind(equity: int | None, capital: int | None) -> str | None:
    """완전잠식(자본총계<0) / 부분잠식(0<=자본총계<자본금) / None."""
    if equity is None or capital is None or capital <= 0:
        return None
    if equity < 0:
        return "완전잠식"
    return "부분잠식" if equity < capital else None


# ───────── 저장소 ─────────

def _dir() -> str:
    d = os.path.join(os.path.dirname(get_settings().corp_index_path) or ".", "screening")
    os.makedirs(d, exist_ok=True)
    return d


def _path(key: str) -> str:
    return os.path.join(_dir(), f"{key}.json")


def _load(key: str) -> dict:
    try:
        with open(_path(key), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def _save(key: str, data: dict) -> None:
    tmp = _path(key) + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    os.replace(tmp, _path(key))


def _name(corp_code: str) -> tuple[str, str]:
    e = corp_index.by_code.get(corp_code)
    return (e.corp_name, e.stock_code) if e else (corp_code, "")


def _listed_codes() -> list[str]:
    return [e.corp_code for e in corp_index.entries if e.is_listed]


# ───────── 자본잠식 수집 ─────────

async def _collect_capital(key: str, year: int, reprt: str) -> None:
    codes = sorted(set(_listed_codes()) | set(await _annual_filings(year)))
    batches = [codes[i:i + _MULTI_BATCH] for i in range(0, len(codes), _MULTI_BATCH)]
    data = {"year": year, "reprt": reprt, "records": {}, "total": len(codes), "complete": False, "updated_at": 0}
    _progress[key] = (0, len(batches))
    for n, batch in enumerate(batches, 1):
        try:
            res = await dart_client.get_json("fnlttMultiAcnt.json", corp_code=",".join(batch),
                                             bsns_year=str(year), reprt_code=reprt)
            data["records"].update(parse_multi_acnt(res.get("list", [])))
        except dart_client.DartNoData:
            pass
        except Exception as e:  # noqa: BLE001  묶음 하나가 실패해도 나머지는 계속
            log.warning("capital batch %s failed: %s", n, e)
        _progress[key] = (n, len(batches))
        await asyncio.sleep(0.2)
    data["complete"] = True
    data["updated_at"] = int(time.time())
    _save(key, data)


# ───────── 감사의견 수집 ─────────

async def _annual_filings(year: int) -> dict[str, str]:
    """결산연도 year의 사업보고서(A001) 제출 법인(비상장 포함) → {corp_code: 'YYYY-MM'}.

    list.json은 corp_code 없이 조회하면 기간이 3개월로 제한되므로 월 단위로 훑는다.
    제출은 결산 이후(12월 결산이면 이듬해 3월 전후)라서 year 1월 ~ year+1 12월을 본다.
    """
    today = date.today()
    out: dict[str, tuple[str, str]] = {}
    for y in (year, year + 1):
        for m in range(1, 13):
            first = date(y, m, 1)
            if first > today:
                break
            last = date(y + (m == 12), m % 12 + 1, 1)
            end = min(date.fromordinal(last.toordinal() - 1), today)
            page = 1
            while page <= 60:
                try:
                    data = await dart_client.get_json(
                        "list.json", bgn_de=first.strftime("%Y%m%d"), end_de=end.strftime("%Y%m%d"),
                        pblntf_detail_ty="A001", page_no=page, page_count=100)
                except dart_client.DartNoData:
                    break
                for it in data.get("list", []):
                    pm = _PERIOD_RE.search(it.get("report_nm", ""))
                    cc = it.get("corp_code", "")
                    if not pm or int(pm.group(1)) != year or cc not in corp_index.by_code:
                        continue
                    prev = out.get(cc)
                    if prev is None or it.get("rcept_no", "") > prev[1]:  # 정정 보고서가 있으면 최신 접수
                        out[cc] = (f"{pm.group(1)}-{pm.group(2)}", it.get("rcept_no", ""))
                if page >= int(data.get("total_page", 1)):
                    break
                page += 1
            await asyncio.sleep(0.1)
    return {cc: v[0] for cc, v in out.items()}


async def _opinion_for(corp_code: str, period: str) -> list[dict] | None:
    y = int(period[:4])
    for year in (y, y - 1):
        try:
            res = await dart_client.get_json("accnutAdtorNmNdAdtOpinion.json", corp_code=corp_code,
                                             bsns_year=str(year), reprt_code=dart_client.REPRT_ANNUAL)
        except dart_client.DartNoData:
            continue
        items = summarize_opinions(res.get("list", []))
        if items:
            return [{"opinion": i["opinion"], "auditor": i["auditor"], "scope": i["scope"]} for i in items]
    return None


async def _collect_audit(key: str, year: int) -> None:
    prev = _load(key)
    records: dict[str, dict] = dict(prev.get("records", {})) if prev.get("year") == year else {}
    filings = await _annual_filings(year)
    todo = [cc for cc in filings if cc not in records]
    data = {"year": year, "records": records, "total": len(filings), "complete": False,
            "updated_at": int(time.time())}
    _progress[key] = (len(filings) - len(todo), len(filings))
    sem = asyncio.Semaphore(_AUDIT_CONCURRENCY)

    async def one(cc: str) -> None:
        async with sem:
            try:
                ops = await _opinion_for(cc, filings[cc])
            except Exception as e:  # noqa: BLE001
                log.warning("audit opinion %s failed: %s", cc, e)
                return
            records[cc] = {"period": filings[cc], "opinions": ops or []}

    for i in range(0, len(todo), _SAVE_EVERY):
        await asyncio.gather(*(one(cc) for cc in todo[i:i + _SAVE_EVERY]))
        data["updated_at"] = int(time.time())
        _progress[key] = (len(records), len(filings))
        _save(key, data)
    data["complete"] = len(records) >= len(filings)
    data["updated_at"] = int(time.time())
    _save(key, data)


# ───────── 조회 ─────────

def _ensure_job(key: str, coro_factory) -> bool:
    task = _jobs.get(key)
    if task and not task.done():
        return True
    _jobs[key] = asyncio.create_task(coro_factory())
    return False


def _status(key: str, data: dict) -> dict:
    done, total = _progress.get(key, (0, 0))
    running = key in _jobs and not _jobs[key].done()
    return {
        "status": "collecting" if running else ("ready" if data.get("complete") else "partial" if data else "empty"),
        "progress": {"done": done, "total": total},
        "updated_at": data.get("updated_at"),
    }


async def audit_nonclean(year: int, refresh: bool = False) -> dict:
    key = f"audit_{year}"
    data = _load(key)
    stale = not data.get("complete") or (refresh and time.time() - data.get("updated_at", 0) > 24 * 3600)
    if stale:
        _ensure_job(key, lambda: _collect_audit(key, year))
        data = _load(key) or data
    items = []
    for cc, rec in data.get("records", {}).items():
        flagged = [o for o in rec.get("opinions", []) if is_flagged_opinion(o["opinion"])]
        if not flagged:
            continue
        name, stock = _name(cc)
        for o in flagged:
            items.append({"corp_code": cc, "corp_name": name, "stock_code": stock, "period": rec["period"],
                          "opinion": o["opinion"], "auditor": o["auditor"], "scope": o["scope"]})
    items.sort(key=lambda x: (x["period"], x["corp_name"]))
    return {**_status(key, data), "year": year, "checked": len(data.get("records", {})), "items": items}


async def capital_impairment(year: int, reprt: str = dart_client.REPRT_ANNUAL, refresh: bool = False) -> dict:
    key = f"capital_{year}_{reprt}"
    data = _load(key)
    if not data.get("complete") or (refresh and time.time() - data.get("updated_at", 0) > 24 * 3600):
        _ensure_job(key, lambda: _collect_capital(key, year, reprt))
        data = _load(key) or data
    items = []
    for cc, rec in data.get("records", {}).items():
        kind = impairment_kind(rec.get("equity"), rec.get("capital"))
        if not kind:
            continue
        name, stock = _name(cc)
        items.append({"corp_code": cc, "corp_name": name, "stock_code": stock, "period": rec["period"],
                      "assets": rec["assets"], "liabilities": rec["liabilities"], "equity": rec["equity"],
                      "capital": rec["capital"], "kind": kind, "fs_div": rec["fs_div"]})
    items.sort(key=lambda x: (x["equity"] if x["equity"] is not None else 0))
    return {**_status(key, data), "year": year, "reprt_code": reprt, "report_name": REPRT_NAMES.get(reprt, reprt),
            "checked": len(data.get("records", {})), "items": items}
