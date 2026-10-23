"""탭 5. 채권·단기증권(회사채, CP, 단기사채 등) 미상환 잔액.

원천: 공공데이터포털 금융위원회 채권기본정보(한국예탁결제원 제공)의 종목별 잔액만 쓴다.
조회가 비거나 실패하면 DART 정기보고서로 대체하지 않고 이유를 그대로 알려 준다.
"""
from __future__ import annotations

import logging
from datetime import date, timedelta

import httpx

from ..cache import cached
from ..config import get_settings
from ..http import get_client
from ..parsers.bond_data import as_list, summarize_outstanding
from .company_service import get_company

log = logging.getLogger(__name__)

KSD_SOURCE_NOTE = "출처: 공공데이터포털 금융위원회 채권기본정보(한국예탁결제원 제공, 종목별 잔액, 공공누리 2유형). 전 영업일 기준이며 비상장·사모 일부는 빠질 수 있습니다."
KSD_UNIT_NOTE = "금액 단위: 억원(종목별 채권잔액)"
KSD_URL = "https://apis.data.go.kr/1160100/GetBondIssuInfoService_V2/getBondBasiInfo_V2"


async def _ksd_page(key: str, crno: str, bas_dt: str | None, page: int) -> tuple[list[dict], int]:
    params = {"serviceKey": key, "resultType": "json", "numOfRows": 500, "pageNo": page, "crno": crno}
    if bas_dt:
        params["basDt"] = bas_dt
    r = await get_client().get(KSD_URL, params=params)
    r.raise_for_status()
    body = r.json().get("response", {}).get("body", {})
    return as_list((body.get("items") or {}).get("item") if isinstance(body.get("items"), dict) else None), int(body.get("totalCount") or 0)


async def _ksd_snapshot(key: str, crno: str, today: date) -> list[dict]:
    """최근 영업일부터 거슬러 올라가며 종목별 잔액을 받는다. 일자 지정이 안 먹으면 기준일 미지정(최신)으로 한 번 더."""
    for back in range(1, 8):
        d = today - timedelta(days=back)
        if d.weekday() >= 5:
            continue
        rows: list[dict] = []
        for page in range(1, 5):
            got, total = await _ksd_page(key, crno, d.strftime("%Y%m%d"), page)
            rows += got
            if len(rows) >= total or not got:
                break
        if rows:
            return rows
    got, _ = await _ksd_page(key, crno, None, 1)
    return got


async def _from_ksd(corp_code: str) -> tuple[dict | None, str]:
    """(결과, 실패 이유). 결과가 없을 때 이유를 화면에 그대로 보여 준다."""
    key = get_settings().data_go_kr_key
    if not key:
        return None, "서버에 공공데이터포털 인증키(DATA_GO_KR_KEY)가 설정되어 있지 않습니다."
    company = await get_company(corp_code)
    crno = (company.get("jurir_no") or "").replace("-", "").strip()
    if not crno:
        return None, "법인등록번호를 확인하지 못해 종목을 조회할 수 없습니다."
    try:
        rows = await _ksd_snapshot(key, crno, date.today())
    except httpx.HTTPStatusError as e:
        code = e.response.status_code
        hint = " 활용신청 승인 전이거나 키가 맞지 않을 수 있습니다." if code in (401, 403) else ""
        log.warning("ksd bond http %s for %s", code, corp_code)
        return None, f"채권기본정보 조회가 거부되었습니다(HTTP {code}).{hint}"
    except Exception as e:  # noqa: BLE001
        log.warning("ksd bond failed for %s: %s", corp_code, type(e).__name__)
        return None, "채권기본정보 조회 중 오류가 발생했습니다. 잠시 뒤 다시 시도해 주세요."
    sections = summarize_outstanding(rows, date.today())
    if not sections:
        return None, ""
    return {
        "corp_code": corp_code,
        "available": True,
        "unit_note": KSD_UNIT_NOTE,
        "source": KSD_SOURCE_NOTE,
        "sections": sections,
        "missing": [],
    }, ""


@cached(ttl=6 * 3600)
async def get_debt_securities(corp_code: str) -> dict:
    ksd, reason = await _from_ksd(corp_code)
    if ksd:
        return ksd
    message = reason or "예탁결제원 채권기본정보에서 이 기업의 미상환 종목을 찾지 못했습니다. 발행 이력이 없거나 사모·비등록 종목만 있는 경우입니다."
    return {
        "corp_code": corp_code,
        "available": False,
        "message": message,
        "unit_note": "",
        "source": KSD_SOURCE_NOTE,
        "sections": [],
        "missing": [],
    }
