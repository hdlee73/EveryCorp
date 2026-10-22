"""금액 파싱·표시 형식."""
from __future__ import annotations

import re

_EOK = 100_000_000          # 1억
_JO = 1_000_000_000_000     # 1조
_MILLION = 1_000_000


def parse_amount(raw: str | int | float | None) -> int | None:
    """DART 금액 문자열('1,234', '-1,234', '(1,234)', '-', '')을 정수(원)로."""
    if raw is None:
        return None
    if isinstance(raw, (int, float)):
        return int(raw)
    s = raw.strip().replace(",", "")
    if s in ("", "-", "N/A"):
        return None
    neg = s.startswith("(") and s.endswith(")")
    s = s.strip("()")
    try:
        v = int(float(s))
    except ValueError:
        return None
    return -v if neg else v


def parse_ratio(raw: str | None) -> float | None:
    if raw is None:
        return None
    s = re.sub(r"[^\d\.\-]", "", raw)
    if s in ("", "-", "."):
        return None
    try:
        return float(s)
    except ValueError:
        return None


def format_krw(amount: int | None) -> str | None:
    """원 단위 금액을 읽기 쉬운 한글 단위로.

    1조 이상: '514조 5,319억원' / 1억 이상: '3,456억원' / 그 미만: '820백만원'
    """
    if amount is None:
        return None
    sign = "-" if amount < 0 else ""
    a = abs(amount)
    if a >= _JO:
        jo, rest = divmod(a, _JO)
        eok = rest // _EOK
        return f"{sign}{jo:,}조 {eok:,}억원" if eok else f"{sign}{jo:,}조원"
    if a >= _EOK:
        return f"{sign}{round(a / _EOK):,}억원"
    return f"{sign}{round(a / _MILLION):,}백만원"
