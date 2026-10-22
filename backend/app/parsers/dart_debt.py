"""채권·단기증권 미상환 잔액 탭: DART 증권 미상환 잔액 API 응답 가공(순수 함수).

DART 정기보고서 '증권의 발행을 통한 자금조달에 관한 사항' 중 미상환 잔액 표를 API로 받는 6종 중 5종을 쓴다
(DS002 '증권의 발행을 통한 자금조달'). 응답은 행(공모/사모/합계)마다 잔여만기 구간별 금액 컬럼이 붙는 형태다.
구간 컬럼 이름이 종류마다 다르므로, 알려진 메타 컬럼을 뺀 나머지 숫자 컬럼을 구간으로 보고 JSON 순서를 유지한다.
"""
from __future__ import annotations

from ..utils.money import parse_amount

# (key, 표시 이름, DART 엔드포인트)
SECTIONS: list[tuple[str, str, str]] = [
    ("CORP_BOND", "회사채", "cprndNrdmpBlce.json"),
    ("COMMERCIAL_PAPER", "기업어음증권", "entrprsBilScritsNrdmpBlce.json"),
    ("SHORT_TERM_BOND", "단기사채", "srtpdPsndbtNrdmpBlce.json"),
    ("HYBRID_CAPITAL", "신종자본증권", "newCaplScritsNrdmpBlce.json"),
    ("CONTINGENT_CAPITAL", "조건부자본증권", "cndlCaplScritsNrdmpBlce.json"),
]

_META = {"rcept_no", "corp_cls", "corp_code", "corp_name", "stlm_dt", "remndr_exprtn1", "remndr_exprtn2",
         "sm", "isu_lmt", "remndr_lmt"}

BUCKET_LABELS = {
    "de10_below": "10일 이하",
    "de10_excess_de30_below": "10일 초과 30일 이하",
    "de30_excess_de90_below": "30일 초과 90일 이하",
    "de90_excess_de180_below": "90일 초과 180일 이하",
    "de180_excess_yy1_below": "180일 초과 1년 이하",
    "yy1_below": "1년 이하",
    "yy1_excess_yy2_below": "1년 초과 2년 이하",
    "yy2_excess_yy3_below": "2년 초과 3년 이하",
    "yy3_excess_yy4_below": "3년 초과 4년 이하",
    "yy4_excess_yy5_below": "4년 초과 5년 이하",
    "yy5_excess_yy10_below": "5년 초과 10년 이하",
    "yy10_excess_yy15_below": "10년 초과 15년 이하",
    "yy15_excess_yy20_below": "15년 초과 20년 이하",
    "yy20_excess_yy30_below": "20년 초과 30년 이하",
    "yy30_excess": "30년 초과",
    "yy10_excess": "10년 초과",
    "yy3_excess": "3년 초과",
}


def bucket_label(key: str) -> str:
    return BUCKET_LABELS.get(key, key)


def _clean(s: str | None) -> str:
    s = (s or "").strip()
    return "" if s in ("-", "") else s


def _is_total_row(label: str) -> bool:
    return "합계" in label or label == "계"


def summarize_section(key: str, title: str, rows: list[dict]) -> dict:
    out_rows: list[dict] = []
    for r in rows:
        label = " ".join(x for x in (_clean(r.get("remndr_exprtn1")), _clean(r.get("remndr_exprtn2"))) if x) or "합계"
        buckets = []
        for k, v in r.items():
            if k in _META:
                continue
            amt = parse_amount(v if isinstance(v, (str, int, float)) else None)
            buckets.append({"key": k, "label": bucket_label(k), "amount": amt})
        total = parse_amount(r.get("sm"))
        if total is None:
            vals = [b["amount"] for b in buckets if b["amount"] is not None]
            total = sum(vals) if vals else None
        out_rows.append({
            "label": label,
            "is_total": _is_total_row(label),
            "total": total,
            "issue_limit": parse_amount(r.get("isu_lmt")),
            "remaining_limit": parse_amount(r.get("remndr_lmt")),
            "buckets": buckets,
        })
    # 섹션 대표 잔액: 합계 행이 있으면 그 값, 없으면 행 합
    totals = [x for x in out_rows if x["is_total"] and x["total"] is not None]
    grand = totals[0]["total"] if totals else (
        sum(x["total"] for x in out_rows if x["total"] is not None) if any(x["total"] is not None for x in out_rows) else None
    )
    return {
        "key": key,
        "title": title,
        "total": grand,
        "total_display": None if grand is None else f"{grand:,}",
        "rows": out_rows,
    }
