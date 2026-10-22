"""KRX Open API (openapi.krx.co.kr) — 전 영업일(T-1) 종가 스냅샷.

KRX Open API는 '기준일 하루의 전 종목' 표를 돌려주는 방식이고 실시간이 아니다.
그래서 (1) 장중 실시간은 KIS/네이버, (2) 두 곳이 모두 실패했을 때의 전일 종가 폴백으로만 쓴다.
1년 일봉을 만들려면 영업일마다 호출해야 해서(약 250회) 차트에는 쓰지 않는다.

주의: 엔드포인트 경로·헤더·필드명은 KRX 이용 신청 후 받는 명세서와 반드시 대조할 것
(작성 시점에 공식 문서에 접속하지 못해 공개된 라이브러리 설명을 기준으로 함).
  GET {base}/sto/stk_bydd_trd?basDd=YYYYMMDD   (유가증권, 코스닥은 ksq_bydd_trd, 코넥스는 knx_bydd_trd)
  헤더 AUTH_KEY: {인증키}
  응답 {"OutBlock_1": [{"BAS_DD","ISU_CD","ISU_NM","MKT_NM","TDD_CLSPRC","CMPPREVDD_PRC","FLUC_RT",
                        "TDD_OPNPRC","TDD_HGPRC","TDD_LWPRC","ACC_TRDVOL", ...}]}
"""
from __future__ import annotations

from datetime import date, timedelta

from ..cache import cached
from ..config import get_settings
from ..errors import UpstreamError
from ..http import get_client
from ..utils.money import parse_amount, parse_ratio

PATHS = {"KOSPI": "sto/stk_bydd_trd", "KOSDAQ": "sto/ksq_bydd_trd", "KONEX": "sto/knx_bydd_trd"}


def krx_enabled() -> bool:
    return bool(get_settings().krx_auth_key)


def parse_snapshot(data: dict, stock_code: str) -> dict | None:
    for r in data.get("OutBlock_1") or []:
        code = str(r.get("ISU_CD", "")).strip()
        if code == stock_code or code.endswith(stock_code) or code[3:9] == stock_code:
            change = parse_amount(r.get("CMPPREVDD_PRC")) or 0
            return {
                "name": r.get("ISU_NM"),
                "price": parse_amount(r.get("TDD_CLSPRC")),
                "change": change,
                "change_rate": parse_ratio(r.get("FLUC_RT")) or 0.0,
                "direction": "UP" if change > 0 else "DOWN" if change < 0 else "FLAT",
                "open": parse_amount(r.get("TDD_OPNPRC")),
                "high": parse_amount(r.get("TDD_HGPRC")),
                "low": parse_amount(r.get("TDD_LWPRC")),
                "volume": parse_amount(r.get("ACC_TRDVOL")),
                "base_date": str(r.get("BAS_DD", "")),
            }
    return None


@cached(ttl=6 * 3600)
async def _snapshot(market: str, bas_dd: str) -> dict:
    s = get_settings()
    r = await get_client().get(f"{s.krx_base_url}/{PATHS[market]}", params={"basDd": bas_dd}, headers={"AUTH_KEY": s.krx_auth_key})
    if r.status_code != 200:
        raise UpstreamError("KRX", f"HTTP {r.status_code}")
    return r.json()


async def previous_close(stock_code: str, market: str) -> dict | None:
    """가장 최근 영업일(최대 7일 전까지)의 종가 정보."""
    if market not in PATHS:
        return None
    d = date.today()
    for _ in range(7):
        d -= timedelta(days=1)
        if d.weekday() >= 5:
            continue
        found = parse_snapshot(await _snapshot(market, d.strftime("%Y%m%d")), stock_code)
        if found:
            return found
    return None
