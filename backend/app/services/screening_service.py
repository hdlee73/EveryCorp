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
from ..cache import cached
from . import dart_client
from .corp_index import corp_index
from .reports_service import summarize_opinions

log = logging.getLogger(__name__)

REPRT_NAMES = {"11011": "사업보고서", "11012": "반기보고서", "11013": "1분기보고서", "11014": "3분기보고서"}
_PERIOD_RE = re.compile(r"\((\d{4})\.(\d{2})\)")
_MULTI_BATCH = 100
_AUDIT_CONCURRENCY = 2  # 화면 요청이 DART 호출을 얻을 수 있도록 백그라운드 수집은 낮게 유지
_BG_PAUSE = 0.2
# DART가 연결을 끊는 상태(IP 차단·한도)에서 계속 두드리면 오래 막히므로, 연속 실패가 쌓이면 수집을 멈추고 쉰다.
_MAX_FAILS = 15
_COOLDOWN = 1800
_fails: dict[str, int] = {}
_cooldown: dict[str, float] = {}
_SAVE_EVERY = 200

# 저장 형식 버전. 올리면 기존 저장본을 버리고 다시 수집한다(v3: 최신 기수만 당기로 봄).
_SCHEMA = 3

_jobs: dict[str, asyncio.Task] = {}
_progress: dict[str, tuple[int, int]] = {}


# ───────── 순수 함수 (테스트 대상) ─────────

def is_clean_opinion(opinion: str) -> bool:
    """'적정'(및 '적정의견')만 적정. '부적정'은 '적정'으로 시작하지 않으므로 비적정."""
    return re.sub(r"\s|의견", "", opinion or "").startswith("적정")


def is_flagged_opinion(opinion: str) -> bool:
    o = (opinion or "").strip()
    return o not in ("", "-") and not is_clean_opinion(o)


def _term_no(term: str) -> int:
    m = re.search(r"제\s*(\d+)\s*기", term or "")
    return int(m.group(1)) if m else -1


def current_term_items(items: list[dict]) -> list[dict]:
    """감사의견 표는 당기·전기·전전기 행이 함께 온다. 그중 해당 결산연도(당기) 행만 남긴다.

    DART는 지난 기수에도 '(당기)'를 붙여 내려주는 경우가 있어(예: 제30기 당기·제29기 당기),
    '당기' 글자가 아니라 가장 큰 기수 번호를 당기로 본다. 기수 번호가 없으면 '당기' 표기, 그것도 없으면 첫 기수.
    같은 (의견, 감사인, 구분)은 하나로 합친다.
    """
    top = max((_term_no(i.get("term", "")) for i in items), default=-1)
    if top >= 0:
        cur = [i for i in items if _term_no(i.get("term", "")) == top]
    else:
        cur = [i for i in items if "당기" in i.get("term", "")] or [i for i in items if items and i.get("term") == items[0].get("term")]
    seen: set[tuple] = set()
    out = []
    for i in cur:
        k = (i["opinion"], i["auditor"], i["scope"])
        if k not in seen:
            seen.add(k)
            out.append(i)
    return out


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


def _digits(s: object) -> str:
    return re.sub(r"\D", "", str(s or ""))


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
            _note(key, True)
        except dart_client.DartNoData:
            _note(key, True)
        except Exception as e:  # noqa: BLE001  묶음 하나가 실패해도 나머지는 계속
            log.warning("capital batch %s failed: %s", n, e)
            _note(key, False)
        _progress[key] = (n, len(batches))
        if _tripped(key):
            break
        await asyncio.sleep(0.2)
    data["complete"] = not _tripped(key)
    data["updated_at"] = int(time.time())
    _save(key, data)


# ───────── CB·BW 수집 ─────────

_CB_LOOKBACK = 8  # 발행결정 공시를 거슬러 볼 햇수(CB·BW 만기는 대개 5년 이내)
_DECISION_APIS = (("cvbdIsDecsn.json", "CB"), ("bdwtIsDecsn.json", "BW"))
_DECISION_WORDS = (("전환사채권발행결정", "CB"), ("신주인수권부사채권발행결정", "BW"))
_REPORT_NODE = (("CB", "미상환전환사채"), ("BW", "미상환신주인수권부사채"))


def _cb_as_of(year: int) -> date:
    return min(date.today(), date(year, 12, 31))


async def _decision_candidates(since: date, until: date) -> set[str]:
    """주요사항보고서 중 CB·BW 발행결정을 낸 상장사. corp_code 없는 list.json은 3개월 제한이라 월 단위로 훑는다."""
    listed = set(_listed_codes())
    out: set[str] = set()
    y, m = since.year, since.month
    while date(y, m, 1) <= until:
        first = date(y, m, 1)
        nxt = date(y + (m == 12), m % 12 + 1, 1)
        end = min(date.fromordinal(nxt.toordinal() - 1), until)
        page = 1
        while page <= 60:
            try:
                data = await dart_client.get_json(
                    "list.json", bgn_de=first.strftime("%Y%m%d"), end_de=end.strftime("%Y%m%d"),
                    pblntf_detail_ty="B001", page_no=page, page_count=100)
            except dart_client.DartNoData:
                break
            for it in data.get("list", []):
                nm = (it.get("report_nm") or "").replace(" ", "")
                if it.get("corp_code") in listed and any(w in nm for w, _ in _DECISION_WORDS):
                    out.add(it["corp_code"])
            if page >= int(data.get("total_page", 1)):
                break
            page += 1
        await asyncio.sleep(_BG_PAUSE)
        y, m = nxt.year, nxt.month
    return out


async def _decision_bonds(cc: str, since: date, until: date) -> list[dict]:
    from ..parsers import convertible as cv

    bonds: list[dict] = []
    for api, kind in _DECISION_APIS:
        try:
            res = await dart_client.get_json(api, corp_code=cc, bgn_de=since.strftime("%Y%m%d"),
                                             end_de=until.strftime("%Y%m%d"))
        except dart_client.DartNoData:
            continue
        bonds += cv.parse_decisions(res.get("list", []), kind)
    return bonds


async def _report_balances(cc: str, year: int) -> dict | None:
    """결산연도 year 사업보고서의 '미상환 전환사채·신주인수권부사채 발행현황' 표 → {CB, BW, period, rcp_no}(못 읽은 종류는 None)."""
    from ..parsers import convertible as cv
    from ..parsers import dart_viewer as dv
    from ..http import get_client
    from .reports_service import fetch_viewer_html

    try:
        data = await dart_client.get_json("list.json", corp_code=cc, bgn_de=f"{year}0101",
                                          end_de=min(date.today(), date(year + 1, 12, 31)).strftime("%Y%m%d"),
                                          pblntf_detail_ty="A001", page_count=100)
    except dart_client.DartNoData:
        return None
    best = None
    for it in data.get("list", []):
        pm = _PERIOD_RE.search(it.get("report_nm", ""))
        if pm and int(pm.group(1)) == year and (best is None or it["rcept_no"] > best[1]):
            best = (f"{pm.group(1)}-{pm.group(2)}", it["rcept_no"])
    if not best:
        return None
    nodes = dv.parse_nodes(await fetch_viewer_html(best[1]))
    out: dict = {"CB": None, "BW": None, "period": best[0], "rcp_no": best[1]}
    for kind, word in _REPORT_NODE:
        node = next((n for n in nodes if word in re.sub(r"\s", "", n.get("text", ""))), None)
        if not node:
            continue
        r = await get_client().get(f"{dv.DART_HOST}/report/viewer.do", params={
            "rcpNo": node.get("rcpNo") or best[1], "dcmNo": node.get("dcmNo"), "eleId": node.get("eleId"),
            "offset": node.get("offset"), "length": node.get("length"), "dtd": node.get("dtd")})
        if r.status_code == 200:
            out[kind] = cv.parse_balance_table(r.text)
    return out


async def _collect_convertible(key: str, year: int) -> None:
    """1) 발행결정 공시로 후보 상장사를 찾고 2) 발행 건(권면총액·만기)을 모은 뒤 3) 사업보고서 미상환 표로 잔액을 확인한다."""
    from ..parsers import convertible as cv

    prev = _load(key)
    keep = prev.get("year") == year and prev.get("v") == _SCHEMA
    records: dict[str, dict] = dict(prev.get("records", {})) if keep else {}
    cands: list[str] = list(prev.get("cands", [])) if keep else []
    until = _cb_as_of(year)
    since = date(until.year - _CB_LOOKBACK, until.month, 1)
    data = {"v": _SCHEMA, "year": year, "records": records, "cands": cands, "cands_done": bool(keep and prev.get("cands_done")),
            "total": len(cands), "complete": False, "updated_at": int(time.time())}
    _progress[key] = (len(records), max(len(cands), 1))
    if not data["cands_done"]:
        cands[:] = sorted(await _decision_candidates(since, until))
        if _tripped(key):
            return
        data.update(cands_done=True, total=len(cands))
        _save(key, data)
    todo = [cc for cc in cands if cc not in records]
    _progress[key] = (len(cands) - len(todo), len(cands))
    sem = asyncio.Semaphore(_AUDIT_CONCURRENCY)
    today = until.strftime("%Y%m%d")

    async def one(cc: str) -> None:
        async with sem:
            if _tripped(key):
                return
            try:
                bonds = await _decision_bonds(cc, since, until)
                rec: dict = {"bonds": bonds, "report": None}
                if cv.live_decisions(bonds, today):  # 만기 전 발행 건이 있는 회사만 보고서를 확인
                    rec["report"] = await _report_balances(cc, year)
                records[cc] = rec
                _note(key, True)
            except Exception as e:  # noqa: BLE001
                log.warning("convertible %s failed: %s", cc, e)
                _note(key, False)
            await asyncio.sleep(_BG_PAUSE)

    for i in range(0, len(todo), 50):
        await asyncio.gather(*(one(cc) for cc in todo[i:i + 50]))
        data["updated_at"] = int(time.time())
        _progress[key] = (len(records), len(cands))
        _save(key, data)
    data["complete"] = all(cc in records for cc in cands)
    data["updated_at"] = int(time.time())
    _save(key, data)


def convertible_item(rec: dict, today: str) -> dict | None:
    """수집 기록 → 화면 항목. 사업보고서 미상환 표가 읽히면 그 잔액을, 아니면 발행결정 기준 추정(권면총액)을 쓴다."""
    from ..parsers import convertible as cv

    live = cv.live_decisions(rec.get("bonds", []), today)
    rep = rec.get("report") or {}
    amounts: dict[str, int] = {}
    source = "발행결정 추정"
    for kind in ("CB", "BW"):
        est = sum(b["amount"] for b in live if b["kind"] == kind)
        got = rep.get(kind)
        if got is None:
            amounts[kind] = est
            continue
        # 보고서 기준일 이후에 납입된 발행 건은 보고서에 없으므로 추정으로 더한다.
        after = sum(b["amount"] for b in live if b["kind"] == kind and _digits(b["issued"])[:6] > _digits(rep.get("period"))[:6])
        amounts[kind] = got + after
        source = "사업보고서"
    total = amounts["CB"] + amounts["BW"]
    if total <= 0:
        return None
    return {"cb_amount": amounts["CB"], "bw_amount": amounts["BW"], "total": total, "count": len(live), "source": source,
            "period": rep.get("period", ""), "rcp_no": rep.get("rcp_no", ""),
            "next_maturity": min((b["maturity"] for b in live if b["maturity"]), default=""),
            "bonds": [{"kind": b["kind"], "issued": b["issued"], "maturity": b["maturity"], "amount": b["amount"], "rate": ""}
                      for b in sorted(live, key=lambda b: b["maturity"] or "99999999")]}


# ───────── 감사의견 수집 ─────────

async def _annual_filings(year: int) -> dict[str, tuple[str, str]]:
    """결산연도 year의 사업보고서(A001) 제출 법인(비상장 포함) → {corp_code: ('YYYY-MM', 접수번호)}.

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
    return out


async def _opinion_for(corp_code: str, period: str) -> list[dict] | None:
    y = int(period[:4])
    for year in (y, y - 1):
        try:
            res = await dart_client.get_json("accnutAdtorNmNdAdtOpinion.json", corp_code=corp_code,
                                             bsns_year=str(year), reprt_code=dart_client.REPRT_ANNUAL)
        except dart_client.DartNoData:
            continue
        items = current_term_items(summarize_opinions(res.get("list", [])))
        if items:
            return [{"opinion": i["opinion"], "auditor": i["auditor"], "scope": i["scope"]} for i in items]
    return None


async def _collect_audit(key: str, year: int) -> None:
    prev = _load(key)
    keep = prev.get("year") == year and prev.get("v") == _SCHEMA
    records: dict[str, dict] = dict(prev.get("records", {})) if keep else {}
    filings = await _annual_filings(year)
    todo = [cc for cc in filings if cc not in records]
    data = {"v": _SCHEMA, "year": year, "records": records, "total": len(filings), "complete": False,
            "updated_at": int(time.time())}
    _progress[key] = (len(filings) - len(todo), len(filings))
    sem = asyncio.Semaphore(_AUDIT_CONCURRENCY)

    async def one(cc: str) -> None:
        async with sem:
            if _tripped(key):
                return
            try:
                ops = await _opinion_for(cc, filings[cc][0])
                _note(key, True)
            except Exception as e:  # noqa: BLE001
                log.warning("audit opinion %s failed: %s", cc, e)
                _note(key, False)
                return
            records[cc] = {"period": filings[cc][0], "rcept_no": filings[cc][1], "opinions": ops or []}
            await asyncio.sleep(_BG_PAUSE)

    for i in range(0, len(todo), _SAVE_EVERY):
        await asyncio.gather(*(one(cc) for cc in todo[i:i + _SAVE_EVERY]))
        data["updated_at"] = int(time.time())
        _progress[key] = (len(records), len(filings))
        _save(key, data)
    data["complete"] = len(records) >= len(filings)
    data["updated_at"] = int(time.time())
    _save(key, data)


# ───────── 조회 ─────────

def _note(key: str, ok: bool) -> None:
    _fails[key] = 0 if ok else _fails.get(key, 0) + 1


def _tripped(key: str) -> bool:
    return _fails.get(key, 0) >= _MAX_FAILS


def _ensure_job(key: str, coro_factory) -> bool:
    task = _jobs.get(key)
    if task and not task.done():
        return True
    if time.time() < _cooldown.get(key, 0):
        return False  # 직전에 연속 실패로 멈췄다. 앱이 계속 조회해도 쉬는 동안은 다시 시작하지 않는다.

    async def run() -> None:
        _fails[key] = 0
        try:
            await coro_factory()
        except Exception as e:  # noqa: BLE001
            log.warning("screening job %s crashed: %s", key, e)
            _cooldown[key] = time.time() + _COOLDOWN
            return
        if _tripped(key):
            log.warning("screening job %s stopped after %d consecutive DART failures; pausing %ds", key, _MAX_FAILS, _COOLDOWN)
            _cooldown[key] = time.time() + _COOLDOWN

    _jobs[key] = asyncio.create_task(run())
    return False


def _status(key: str, data: dict) -> dict:
    done, total = _progress.get(key, (0, 0))
    running = key in _jobs and not _jobs[key].done()
    return {
        "status": "collecting" if running else ("ready" if data.get("complete") else "partial" if data else "empty"),
        "progress": {"done": done, "total": total},
        "updated_at": data.get("updated_at"),
        "paused": time.time() < _cooldown.get(key, 0),
    }


async def audit_nonclean(year: int, start: bool = False, refresh: bool = False) -> dict:
    key = f"audit_{year}"
    data = _load(key)
    if data.get("v") != _SCHEMA:
        data = {}  # 이전 형식 저장본은 쓰지 않고 새로 수집한다
    stale = not data.get("complete") or (refresh and time.time() - data.get("updated_at", 0) > 24 * 3600)
    if stale and start:
        _ensure_job(key, lambda: _collect_audit(key, year))
        fresh = _load(key)
        data = fresh if fresh.get("v") == _SCHEMA else data
    items = []
    for cc, rec in data.get("records", {}).items():
        flagged = [o for o in rec.get("opinions", []) if is_flagged_opinion(o["opinion"])]
        if not flagged:
            continue
        name, stock = _name(cc)
        for o in flagged:
            items.append({"corp_code": cc, "corp_name": name, "stock_code": stock, "period": rec["period"],
                          "rcept_no": rec.get("rcept_no", ""), "opinion": o["opinion"], "auditor": o["auditor"], "scope": o["scope"]})
    items.sort(key=lambda x: (x["period"], x["corp_name"]))
    return {**_status(key, data), "year": year, "checked": len(data.get("records", {})), "items": items}


async def capital_impairment(year: int, reprt: str = dart_client.REPRT_ANNUAL, start: bool = False,
                             refresh: bool = False) -> dict:
    key = f"capital_{year}_{reprt}"
    data = _load(key)
    if start and (not data.get("complete") or (refresh and time.time() - data.get("updated_at", 0) > 24 * 3600)):
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


async def convertible_bonds(year: int, start: bool = False, refresh: bool = False) -> dict:
    """CB·BW 잔액이 있는 상장사. 발행결정 공시로 후보를 찾고 사업보고서 미상환 표로 잔액을 확인한다(못 읽으면 권면총액 추정)."""
    key = f"cbbw_{year}"
    data = _load(key)
    if start and (not data.get("complete") or (refresh and time.time() - data.get("updated_at", 0) > 24 * 3600)):
        _ensure_job(key, lambda: _collect_convertible(key, year))
        data = _load(key) or data
    today = _cb_as_of(year).strftime("%Y%m%d")
    items = []
    for cc, rec in data.get("records", {}).items():
        it = convertible_item(rec, today)
        if not it:
            continue
        name, stock = _name(cc)
        items.append({"corp_code": cc, "corp_name": name, "stock_code": stock, **it})
    items.sort(key=lambda x: -x["total"])
    return {**_status(key, data), "year": year, "checked": len(data.get("records", {})), "candidates": len(data.get("cands", [])),
            "items": items}


def auditor_in_text(auditor: str, text: str) -> bool:
    """감사보고서 본문에 감사인 이름이 들어 있는가. 괄호(등록번호 등)와 공백은 무시한다."""
    name = re.sub(r"\(.*?\)|\s", "", auditor)
    return not name or name in re.sub(r"\s", "", text)


async def _doc_text(rcp_no: str, dcm_no: str) -> str:
    """첨부 문서 첫 목차 노드의 본문 텍스트(감사보고서 서명부의 감사인 이름 확인용)."""
    from ..http import get_client
    from ..parsers import audit_notes as an
    from ..parsers import dart_viewer as dv

    r = await get_client().get(f"{dv.DART_HOST}/dsaf001/main.do", params={"rcpNo": rcp_no, "dcmNo": dcm_no})
    nodes = dv.parse_nodes(r.text) if r.status_code == 200 else []
    node = next((n for n in nodes if "감사" in n.get("text", "")), nodes[0] if nodes else None)
    if not node:
        raise ValueError("no node")
    r = await get_client().get(f"{dv.DART_HOST}/report/viewer.do", params={
        "rcpNo": node.get("rcpNo") or rcp_no, "dcmNo": node.get("dcmNo") or dcm_no, "eleId": node.get("eleId"),
        "offset": node.get("offset"), "length": node.get("length"), "dtd": node.get("dtd")})
    if r.status_code != 200:
        raise ValueError(f"viewer {r.status_code}")
    return an.html_to_text(r.text)


@cached(ttl=7 * 24 * 3600)
async def audit_report_link(rcp_no: str, auditor: str = "") -> dict:
    """사업보고서 접수번호 → 그 보고서에 첨부된 감사보고서 뷰어 주소(연결 우선).

    auditor가 주어지면 본문에 그 감사인 이름이 있는 문서를 고른다. 본문을 읽지 못한 문서는 판단 불가로 두고,
    읽은 문서가 모두 다른 감사인이면 엉뚱한 보고서를 열지 않도록 사업보고서 본문 주소를 돌려준다.
    첨부를 못 찾으면 사업보고서 본문 주소.
    """
    from ..parsers import dart_viewer as dv
    from .reports_service import fetch_viewer_html

    main = {"url": dv.viewer_url(rcp_no), "title": "사업보고서", "found": False}
    try:
        docs = [d for d in dv.parse_attachments(await fetch_viewer_html(rcp_no))
                if dv.is_audit_or_review(d.title) and dv.classify(d.title)[0] == "AUDIT"]
    except Exception as e:  # noqa: BLE001
        log.warning("audit link %s failed: %s", rcp_no, e)
        return main
    if not docs:
        return main
    if auditor.strip():
        match: list = []
        unknown: list = []
        for d in docs[:4]:
            try:
                if auditor_in_text(auditor, await _doc_text(d.rcp_no, d.dcm_no)):
                    match.append(d)
            except Exception as e:  # noqa: BLE001
                log.warning("audit doc %s/%s unreadable: %s", d.rcp_no, d.dcm_no, e)
                unknown.append(d)
        docs = match or unknown
        if not docs:
            return {**main, "title": "사업보고서 (해당 감사인의 감사보고서를 첨부에서 찾지 못했습니다)"}
    best = max(docs, key=lambda d: dv.classify(d.title)[1])
    return {"url": dv.viewer_url(best.rcp_no, best.dcm_no), "title": best.title, "found": True}
