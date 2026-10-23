"""기업개황 탭 가공 로직(순수 함수, 네트워크 없음 → 단위 테스트 대상).

1) 정기보고서 이름 → (사업연도, 보고서코드) 해석
2) 최대주주현황(hyslrSttus) → "최대주주 OOO 외 특수관계인 N명 (합산 지분율 XX.X%)"
3) 전체 재무제표(fnlttSinglAcntAll) → 자산/부채/자본, 매출액(또는 영업수익)/영업이익/당기순이익
"""
from __future__ import annotations

import re
from dataclasses import dataclass

from ..utils.money import format_krw, parse_amount, parse_ratio

# ───────────────────────── 1) 정기보고서 해석 ─────────────────────────

_PERIODIC_RE = re.compile(r"(사업|반기|분기)보고서\s*\((\d{4})\.(\d{2})\)")


@dataclass(frozen=True, slots=True)
class PeriodicReport:
    kind: str           # 사업 | 반기 | 분기
    period_year: int    # 보고 기간 종료 연도
    period_month: int   # 보고 기간 종료 월
    rcept_no: str
    rcept_dt: str
    report_nm: str

    @property
    def period_key(self) -> int:
        return self.period_year * 100 + self.period_month


def parse_periodic_reports(items: list[dict]) -> list[PeriodicReport]:
    """list.json(pblntf_ty=A) 결과에서 사업/반기/분기보고서만 뽑아 최신 기간 순으로.

    같은 기간의 정정보고서가 여러 건이면 가장 최근 접수건만 남긴다.
    """
    best: dict[tuple[str, int], PeriodicReport] = {}
    for it in items:
        m = _PERIODIC_RE.search(it.get("report_nm", ""))
        if not m:
            continue
        r = PeriodicReport(m.group(1), int(m.group(2)), int(m.group(3)), it["rcept_no"], it.get("rcept_dt", ""), it["report_nm"].strip())
        key = (r.kind, r.period_key)
        if key not in best or r.rcept_no > best[key].rcept_no:
            best[key] = r
    return sorted(best.values(), key=lambda r: (r.period_key, r.rcept_no), reverse=True)


def reprt_code_for(report: PeriodicReport, fiscal_month: int = 12) -> str:
    """보고서 종류·기간 → DART reprt_code. 분기는 결산월 기준 1분기/3분기를 구분한다."""
    if report.kind == "사업":
        return "11011"
    if report.kind == "반기":
        return "11012"
    q1_month = (fiscal_month % 12) + 3  # 12월 결산 → 3월, 3월 결산 → 6월
    q1_month = q1_month - 12 if q1_month > 12 else q1_month
    return "11013" if report.period_month == q1_month else "11014"


def bsns_year_candidates(report: PeriodicReport, fiscal_month: int = 12) -> list[str]:
    """DART bsns_year 후보. 12월 결산은 기간 종료 연도, 그 외 결산월은 두 해 모두 시도한다."""
    y = report.period_year
    if fiscal_month == 12:
        return [str(y)]
    return [str(y), str(y - 1)]


# ───────────────────────── 2) 최대주주 ─────────────────────────

_TOTAL_NAMES = {"계", "합계", "총계", "소계"}


def _is_common(stock_knd: str) -> bool:
    k = (stock_knd or "").replace(" ", "")
    return k == "" or "보통" in k or k == "-"


def summarize_shareholders(rows: list[dict]) -> dict:
    """hyslrSttus.json의 list → 표시용 요약.

    규칙
    - 보통주 기준(보통주 행이 없으면 전체 행).
    - 최대주주: relate가 '본인'/'최대주주'인 행, 없으면 기말 지분율 최대 행.
    - 특수관계인 수: 합계 행·최대주주 본인을 제외한 서로 다른 이름 수.
    - 합산 지분율: 보통주 '계' 행의 기말 지분율, 없으면 개별 행 합.
    """
    common = [r for r in rows if _is_common(r.get("stock_knd", ""))] or rows
    totals = [r for r in common if (r.get("nm") or "").strip() in _TOTAL_NAMES]
    holders = [r for r in common if (r.get("nm") or "").strip() not in _TOTAL_NAMES and (r.get("nm") or "").strip()]
    if not holders:
        return {"available": False, "display": "최대주주 정보가 없습니다.", "holders": []}

    def ratio(r: dict) -> float:
        return parse_ratio(r.get("trmend_posesn_stock_qota_rt")) or 0.0

    largest = next(
        (r for r in holders if any(k in (r.get("relate") or "") for k in ("본인", "최대주주"))),
        None,
    ) or max(holders, key=ratio)
    largest_name = largest["nm"].strip()
    related_names = {r["nm"].strip() for r in holders} - {largest_name}

    total_ratio = parse_ratio(totals[0].get("trmend_posesn_stock_qota_rt")) if totals else None
    if total_ratio is None:
        total_ratio = round(sum(ratio(r) for r in holders), 2)

    n = len(related_names)
    display = (
        f"최대주주 {largest_name} 외 특수관계인 {n}명 (합산 지분율 {total_ratio:.1f}%)"
        if n
        else f"최대주주 {largest_name} (지분율 {total_ratio:.1f}%)"
    )
    return {
        "available": True,
        "name": largest_name,
        "related_count": n,
        "total_ratio": total_ratio,
        "display": display,
        "holders": [
            {
                "name": r["nm"].strip(),
                "relation": (r.get("relate") or "").strip() or None,
                "shares": parse_amount(r.get("trmend_posesn_stock_co")),
                "ratio": parse_ratio(r.get("trmend_posesn_stock_qota_rt")),
            }
            for r in sorted(holders, key=ratio, reverse=True)
        ],
    }


# ───────────────────────── 3) 재무 ─────────────────────────

# account_id(XBRL 표준) 우선, 없으면 계정명으로 찾는다.
_BS = {
    "total_assets": (("ifrs-full_Assets",), ("자산총계",)),
    "total_liabilities": (("ifrs-full_Liabilities",), ("부채총계",)),
    "total_equity": (("ifrs-full_Equity",), ("자본총계",)),
}
_REVENUE_IDS = ("ifrs-full_Revenue", "ifrs_Revenue")
_REVENUE_NAMES = ("매출액", "수익(매출액)", "매출", "영업수익", "순영업수익", "영업수익(매출액)", "매출및지분법손익")
_OPINC_IDS = ("dart_OperatingIncomeLoss", "ifrs-full_ProfitLossFromOperatingActivities")
_OPINC_NAMES = ("영업이익", "영업이익(손실)", "영업손익", "영업손실")
_NI_IDS = ("ifrs-full_ProfitLoss",)
_NI_NAMES = ("당기순이익", "당기순이익(손실)", "당기순손익", "당기순손실", "연결당기순이익", "연결당기순이익(손실)")

_LABEL_DEFAULT = {
    "total_assets": "자산총계",
    "total_liabilities": "부채총계",
    "total_equity": "자본총계",
    "revenue": "매출액",
    "operating_income": "영업이익",
    "net_income": "당기순이익",
}


_EPS_IDS = ("ifrs-full_BasicEarningsLossPerShare",)
_EPS_NAMES = ("기본주당이익", "기본주당이익(손실)", "기본주당순이익", "기본주당순이익(손실)", "기본주당손익", "주당이익", "주당순이익")
_NI_PARENT_IDS = ("ifrs-full_ProfitLossAttributableToOwnersOfParent",)
_NI_PARENT_NAMES = ("지배기업의소유주에게귀속되는당기순이익", "지배기업의소유주에게귀속되는당기순이익(손실)", "지배기업소유주지분")
_EQ_PARENT_IDS = ("ifrs-full_EquityAttributableToOwnersOfParent",)
_EQ_PARENT_NAMES = ("지배기업의소유주에게귀속되는자본", "지배기업소유주지분", "지배기업의소유주에게귀속되는지분")


def _clean_nm(nm: str) -> str:
    return re.sub(r"\s+", "", nm or "")


def _find(rows: list[dict], sj: tuple[str, ...], ids: tuple[str, ...], names: tuple[str, ...]) -> dict | None:
    cand = [r for r in rows if r.get("sj_div") in sj]
    for r in cand:
        if r.get("account_id") in ids:
            return r
    clean = {_clean_nm(n) for n in names}
    for r in cand:
        if _clean_nm(r.get("account_nm", "")) in clean:
            return r
    return None


def revenue_label(account_nm: str | None, industry_code: str | None = None) -> str:
    """매출 계정 라벨. 계정명에 '영업수익'이 있으면 '영업수익'(금융업·지주사 등), 아니면 '매출액'."""
    nm = _clean_nm(account_nm or "")
    if "영업수익" in nm:
        return "영업수익"
    # 매출 계정을 못 찾았는데 금융·보험업(KSIC 64~66)이면 영업수익으로 표기
    if not nm and (industry_code or "")[:2] in ("64", "65", "66"):
        return "영업수익"
    return "매출액"


def _item(key: str, row: dict | None, label: str | None = None) -> dict:
    if row is None:
        return {"label": label or _LABEL_DEFAULT[key], "account_nm": None, "amount": None, "display": None,
                "prev_amount": None, "prev2_amount": None}
    amt = parse_amount(row.get("thstrm_amount"))
    return {
        "label": label or _LABEL_DEFAULT[key],
        "account_nm": (row.get("account_nm") or "").strip(),
        "amount": amt,
        "display": format_krw(amt),
        "prev_amount": parse_amount(row.get("frmtrm_amount")),
        "prev2_amount": parse_amount(row.get("bfefrmtrm_amount")),
    }


def summarize_financials(rows: list[dict], industry_code: str | None = None) -> dict:
    """fnlttSinglAcntAll.json의 list → 재무/손익 요약."""
    bs = {k: _item(k, _find(rows, ("BS",), ids, names)) for k, (ids, names) in _BS.items()}

    is_sj = ("IS", "CIS")
    rev_row = _find(rows, is_sj, _REVENUE_IDS, _REVENUE_NAMES)
    op_row = _find(rows, is_sj, _OPINC_IDS, _OPINC_NAMES)
    ni_row = _find(rows, ("IS",), _NI_IDS, _NI_NAMES) or _find(rows, ("CIS",), _NI_IDS, _NI_NAMES)
    rev_lbl = revenue_label(rev_row.get("account_nm") if rev_row else None, industry_code)
    inc = {
        "revenue": _item("revenue", rev_row, rev_lbl),
        "operating_income": _item("operating_income", op_row),
        "net_income": _item("net_income", ni_row),
    }
    currency = next((r.get("currency") for r in rows if r.get("currency")), "KRW")
    return {"revenue_label": rev_lbl, "currency": currency, "balance_sheet": bs, "income_statement": inc}


# ───────────────────────── 4) 기간 비교 표 ─────────────────────────

def _account_rows(rows: list[dict], industry_code: str | None) -> dict:
    is_sj = ("IS", "CIS")
    rev = _find(rows, is_sj, _REVENUE_IDS, _REVENUE_NAMES)
    return {
        "revenue_label": revenue_label(rev.get("account_nm") if rev else None, industry_code),
        "assets": _find(rows, ("BS",), *_BS["total_assets"]),
        "liabilities": _find(rows, ("BS",), *_BS["total_liabilities"]),
        "equity": _find(rows, ("BS",), *_BS["total_equity"]),
        "revenue": rev,
        "operating": _find(rows, is_sj, _OPINC_IDS, _OPINC_NAMES),
        "net": _find(rows, ("IS",), _NI_IDS, _NI_NAMES) or _find(rows, ("CIS",), _NI_IDS, _NI_NAMES),
        "eps": _find(rows, is_sj, _EPS_IDS, _EPS_NAMES),
        "ni_parent": _find(rows, is_sj, _NI_PARENT_IDS, _NI_PARENT_NAMES),
        "equity_parent": _find(rows, ("BS",), _EQ_PARENT_IDS, _EQ_PARENT_NAMES),
    }


def _v(row: dict | None, field: str) -> int | None:
    return parse_amount(row.get(field)) if row else None


def _debt_ratio(liab: int | None, equity: int | None) -> float | None:
    if liab is None or not equity or equity <= 0:
        return None
    return round(liab / equity * 100, 1)


def cumulative_months(period_month: int, fiscal_month: int) -> int:
    """결산월 기준 누적 개월 수(12월 결산 6월 → 6, 3월 결산 6월 → 3, 3월 결산 12월 → 9)."""
    n = (period_month - fiscal_month) % 12
    return n or 12


def _range_label(year: int, end_month: int, months: int) -> str:
    start = (end_month - months) % 12 + 1
    return f"{year}.{start:02d}~{end_month:02d}"


def build_comparison_tables(
    latest_rows: list[dict] | None, latest_kind: str, latest_year: int, latest_month: int,
    annual_rows: list[dict] | None, annual_year: int | None,
    fiscal_month: int, industry_code: str | None,
) -> dict:
    """최직근 보고서(분기·반기·사업) + 최근 사업보고서로 재무 비교표를 만든다.

    - 재무상태: [최직근 기말(분기·반기일 때만)] + 최근 3개 연말
    - 손익: 분기·반기일 때 [분기 손익, 누적 손익] + 직전 2개 연간 / 사업보고서가 최직근이면 연간 3개
    금액은 원 단위 정수, 부채비율은 %. 없는 값은 None.
    """
    latest = _account_rows(latest_rows, industry_code) if latest_rows else None
    annual = _account_rows(annual_rows, industry_code) if annual_rows else None
    is_annual = latest_kind == "사업"
    rev_label = (latest or annual or {}).get("revenue_label", "매출액")

    # 재무상태표 열
    bs_cols: list[dict] = []
    bs_sets: list[tuple[int | None, int | None, int | None]] = []   # (자산, 부채, 자본)

    def add_bs(label: str, a: dict | None, field: str, sub: str | None = None) -> None:
        bs_cols.append({"label": label, "sub": sub})
        bs_sets.append((_v(a and a["assets"], field), _v(a and a["liabilities"], field), _v(a and a["equity"], field)))

    if not is_annual and latest:
        add_bs(f"{latest_year}.{latest_month:02d}", latest, "thstrm_amount", f"{latest_kind}말")
    if annual and annual_year:
        for i, field in enumerate(("thstrm_amount", "frmtrm_amount", "bfefrmtrm_amount")):
            add_bs(f"{annual_year - i}.{fiscal_month:02d}", annual, field)
    elif latest and is_annual:
        for i, field in enumerate(("thstrm_amount", "frmtrm_amount", "bfefrmtrm_amount")):
            add_bs(f"{latest_year - i}.{fiscal_month:02d}", latest, field)

    balance = {
        "columns": bs_cols,
        "rows": [
            {"label": "자산총계", "values": [s[0] for s in bs_sets]},
            {"label": "부채총계", "values": [s[1] for s in bs_sets]},
            {"label": "자본총계", "values": [s[2] for s in bs_sets]},
            {"label": "부채비율", "unit": "%", "values": [_debt_ratio(s[1], s[2]) for s in bs_sets]},
        ],
    }

    # 손익 열: (라벨, 부제, 계정행 dict, 필드)
    inc_cols: list[dict] = []
    inc_vals: list[tuple[int | None, int | None, int | None]] = []   # (매출, 영업이익, 순이익)

    def add_inc(label: str, sub: str | None, a: dict | None, field: str, fallback: str | None = None) -> None:
        def get(row: dict | None) -> int | None:
            v = _v(row, field)
            return v if v is not None or fallback is None else _v(row, fallback)
        inc_cols.append({"label": label, "sub": sub})
        inc_vals.append((get(a and a["revenue"]), get(a and a["operating"]), get(a and a["net"])))

    if not is_annual and latest:
        cum = cumulative_months(latest_month, fiscal_month)
        add_inc("분기손익", _range_label(latest_year, latest_month, 3), latest, "thstrm_amount")
        add_inc("누적손익", _range_label(latest_year, latest_month, cum), latest, "thstrm_add_amount", fallback="thstrm_amount")
        if annual and annual_year:
            add_inc(f"{annual_year}년 연간", "전년말", annual, "thstrm_amount")
            add_inc(f"{annual_year - 1}년 연간", "전전년말", annual, "frmtrm_amount")
    else:
        src = latest if latest else annual
        year = latest_year if latest else (annual_year or 0)
        if src:
            for i, field in enumerate(("thstrm_amount", "frmtrm_amount", "bfefrmtrm_amount")):
                add_inc(f"{year - i}년 연간", "최근 결산" if i == 0 else None, src, field)

    income = {
        "columns": inc_cols,
        "rows": [
            {"label": rev_label, "values": [s[0] for s in inc_vals]},
            {"label": "영업이익", "values": [s[1] for s in inc_vals]},
            {"label": "당기순이익", "values": [s[2] for s in inc_vals]},
        ],
    }
    return {"unit": "억원", "balance": balance, "income": income}


# ───────────────────────── 5) 최근 5개년 추이 ─────────────────────────

def _year_values(rows: list[dict], year: int, industry_code: str | None) -> dict[int, dict]:
    """사업보고서 전체 재무제표 한 건 → {연도: {revenue, operating, net, assets, liabilities, equity}} (당기·전기·전전기)."""
    acc = _account_rows(rows, industry_code)
    out: dict[int, dict] = {}
    for offset, field in ((0, "thstrm_amount"), (1, "frmtrm_amount"), (2, "bfefrmtrm_amount")):
        out[year - offset] = {
            "revenue": _v(acc["revenue"], field), "operating": _v(acc["operating"], field), "net": _v(acc["net"], field),
            "assets": _v(acc["assets"], field), "liabilities": _v(acc["liabilities"], field), "equity": _v(acc["equity"], field),
            "eps": _v(acc["eps"], field),
            # 지배주주 귀속분이 없으면(별도재무제표 등) 전체 순이익·자본을 쓴다.
            "ni_parent": _v(acc["ni_parent"], field) if acc["ni_parent"] else _v(acc["net"], field),
            "equity_parent": _v(acc["equity_parent"], field) if acc["equity_parent"] else _v(acc["equity"], field),
        }
    return out


def _valuation(v: dict, close: int | None) -> tuple[float | None, float | None]:
    """연말 종가 ÷ EPS = PER, 연말 종가 ÷ BPS = PBR. BPS 는 지배주주 자본 ÷ (지배주주 순이익 ÷ EPS = 가중평균 주식수)."""
    eps = v.get("eps")
    if not close or not eps or eps <= 0:
        return None, None
    per = round(close / eps, 2)
    ni, eq = v.get("ni_parent"), v.get("equity_parent")
    pbr = None
    if ni and ni > 0 and eq and eq > 0:
        shares = ni / eps
        pbr = round(close / (eq / shares), 2) if shares > 0 else None
    return per, pbr


def build_trend(recent_rows: list[dict] | None, recent_year: int | None,
                older_rows: list[dict] | None, older_year: int | None,
                industry_code: str | None = None, closes: dict[int, int] | None = None) -> dict | None:
    """사업보고서 2건(최근, 2년 전)으로 최대 5개 연도의 매출·영업이익·순이익·부채비율 시계열.

    같은 연도가 겹치면 최근 보고서 값을 쓴다(정정 반영). 값이 하나도 없는 연도는 뺀다.
    """
    merged: dict[int, dict] = {}
    label = "매출액"
    for rows, year in ((older_rows, older_year), (recent_rows, recent_year)):  # 최근 값이 나중에 덮어씀
        if rows and year:
            merged.update({y: v for y, v in _year_values(rows, year, industry_code).items()})
            label = _account_rows(rows, industry_code)["revenue_label"]
    years = sorted(y for y, v in merged.items() if any(x is not None for x in v.values()))
    if len(years) < 2:
        return None
    def series(key: str, name: str, fn) -> dict:
        return {"key": key, "label": name, "values": [fn(merged[y]) for y in years]}
    return {
        "unit": "원", "years": years,
        "series": [
            series("revenue", label, lambda v: v["revenue"]),
            series("operating_income", "영업이익", lambda v: v["operating"]),
            series("net_income", "당기순이익", lambda v: v["net"]),
            series("debt_ratio", "부채비율(%)", lambda v: _debt_ratio(v["liabilities"], v["equity"])),
        ]
        + ([series("eps", "EPS(원)", lambda v: v["eps"])] if any(merged[y]["eps"] is not None for y in years) else [])
        + (
            [
                {"key": "per", "label": "PER(배)", "values": [_valuation(merged[y], (closes or {}).get(y))[0] for y in years]},
                {"key": "pbr", "label": "PBR(배)", "values": [_valuation(merged[y], (closes or {}).get(y))[1] for y in years]},
            ]
            if closes
            else []
        ),
    }
