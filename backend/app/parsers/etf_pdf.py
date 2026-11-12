"""ETF 기초자료·구성종목(PDF, Portfolio Deposit File) 파서와 ETF 간 관련성 계산(순수 함수).

데이터 출처(우선순위):
- 순자산총액·상장좌수·NAV: KRX Open API 증권상품 ETF 일별매매정보(etp/etf_bydd_trd) → KRX 정보데이터시스템 ETF 전종목 시세(MDCSTAT04301)
- 운용사·총보수·ISIN: KRX 정보데이터시스템 ETF 전종목 기본정보(MDCSTAT04601) → 브랜드명으로 운용사 추정
- 구성종목: KRX 정보데이터시스템 ETF PDF(MDCSTAT05001, 전 종목) → 네이버 증권 ETF 분석(상위 10종목만)
키움 REST API 에는 구성종목(PDF) TR 이 없다(ka40001~ka40010 중 해당 없음).
필드명은 공개 라이브러리(pykrx 등) 설명 기준이며 `python -m app.diag --etf-pdf` 로 실서버 응답을 확인한다.
"""
from __future__ import annotations

import re
from typing import Iterable

from ..utils.text import normalize_name

# ───────────────────────── 숫자·식별자 ─────────────────────────

def num(v) -> float | None:
    """'1,234.5' / '-' / '' / 숫자 → float. 부호는 유지한다."""
    if v is None:
        return None
    if isinstance(v, (int, float)):
        return float(v)
    s = str(v).strip().replace(",", "")
    if not s or s in {"-", "--"}:
        return None
    try:
        return float(s)
    except ValueError:
        return None


def _int(v) -> int | None:
    f = num(v)
    return None if f is None else int(round(f))


def _txt(v) -> str:
    s = str(v).strip() if v is not None else ""
    return "" if s == "-" else s


_ISIN_RE = re.compile(r"^[A-Z]{2}[0-9A-Z]{9}[0-9]$")
_SHORT_RE = re.compile(r"^[0-9][0-9A-Z]{5}$")


def isin_check_digit(body11: str) -> int:
    """ISIN 앞 11자리의 검증 숫자(ISO 6166: 문자→숫자 변환 후 Luhn)."""
    digits = "".join(str(int(c, 36)) for c in body11.upper())
    total = 0
    for i, ch in enumerate(reversed(digits)):
        d = int(ch)
        if i % 2 == 0:  # 오른쪽 끝부터 홀수 번째를 두 배
            d *= 2
            if d > 9:
                d -= 9
        total += d
    return (10 - total % 10) % 10


def kr_isin(short_code: str) -> str:
    """국내 상장 주식·ETF 단축코드(6자리) → ISIN. 예) 069500 → KR7069500007."""
    body = f"KR7{short_code.upper()}00"
    return body + str(isin_check_digit(body))


def short_of(code: str) -> str:
    """ISIN(KR7xxxxxx00C) 이면 단축코드 6자리, 이미 6자리면 그대로, 아니면 ''."""
    c = (code or "").strip().upper()
    if c.startswith("A") and len(c) == 7 and _SHORT_RE.match(c[1:]):
        c = c[1:]
    if _SHORT_RE.match(c):
        return c
    if _ISIN_RE.match(c) and c.startswith("KR7"):
        return c[3:9]
    return ""


# ───────────────────────── 운용사 ─────────────────────────
# 브랜드 → 운용사. KRX 기본정보(COM_ABBRV)를 받지 못했을 때의 대체 수단. 옛 브랜드도 같은 운용사로 묶는다.
BRAND_MANAGER: dict[str, str] = {
    "KODEX": "삼성자산운용",
    "TIGER": "미래에셋자산운용",
    "RISE": "KB자산운용", "KBSTAR": "KB자산운용",
    "ACE": "한국투자신탁운용", "KINDEX": "한국투자신탁운용",
    "SOL": "신한자산운용",
    "HANARO": "NH-Amundi자산운용",
    "PLUS": "한화자산운용", "ARIRANG": "한화자산운용",
    "KIWOOM": "키움투자자산운용", "KOSEF": "키움투자자산운용", "HEROES": "키움투자자산운용",
    "TIMEFOLIO": "타임폴리오자산운용",
    "WOORI": "우리자산운용", "WON": "우리자산운용",
    "1Q": "하나자산운용",
    "BNK": "BNK자산운용",
    "TRUSTON": "트러스톤자산운용",
    "KCGI": "KCGI자산운용",
    "FOCUS": "브이아이자산운용",
    "DAISHIN343": "대신자산운용",
    "UNICORN": "현대자산운용",
    "파워": "교보악사자산운용", "POWER": "교보악사자산운용",
    "마이티": "DB자산운용",
    "에셋플러스": "에셋플러스자산운용",
    "마이다스": "마이다스에셋자산운용",
    "흥국": "흥국자산운용",
    "VITA": "한국투자밸류자산운용",
    "ITF": "IBK자산운용",
    "HK": "흥국자산운용",
    "KOACT": "삼성액티브자산운용",
    "BNKP": "BNK자산운용",
}

_MANAGER_SUFFIX = re.compile(r"(주식회사|\(주\)|㈜|\s)")


def brand_of(etf_name: str) -> str:
    first = (etf_name or "").strip().split(" ")[0] if etf_name else ""
    return first.upper() if first.isascii() else first


def manager_key(manager: str) -> str:
    """'삼성자산운용(주)' · '삼성자산운용' 을 같은 키로. 비교용."""
    return _MANAGER_SUFFIX.sub("", manager or "").replace("투자신탁운용", "자산운용").lower()


def manager_of(etf_name: str, krx_manager: str = "") -> tuple[str, str]:
    """(운용사, 근거) 근거는 'krx'(거래소 기본정보) 또는 'brand'(브랜드명 추정) 또는 ''."""
    if krx_manager:
        return krx_manager, "krx"
    m = BRAND_MANAGER.get(brand_of(etf_name), "")
    return (m, "brand") if m else ("", "")


# ───────────────────────── KRX 응답 파서 ─────────────────────────

def _rows(data: dict, *keys: str) -> list[dict]:
    for k in keys:
        v = data.get(k) if isinstance(data, dict) else None
        if isinstance(v, list):
            return [r for r in v if isinstance(r, dict)]
    return []


def parse_krx_basic(data: dict) -> dict[str, dict]:
    """MDCSTAT04601(ETF 전종목 기본정보) → {단축코드: {...}}."""
    out: dict[str, dict] = {}
    for r in _rows(data, "output", "OutBlock_1"):
        code = short_of(_txt(r.get("ISU_SRT_CD"))) or short_of(_txt(r.get("ISU_CD")))
        if not code:
            continue
        fee = num(r.get("ETF_TOT_FEE"))
        out[code] = {
            "code": code,
            "isin": _txt(r.get("ISU_CD")) if _ISIN_RE.match(_txt(r.get("ISU_CD"))) else kr_isin(code),
            "name": _txt(r.get("ISU_ABBRV")) or _txt(r.get("ISU_NM")),
            "manager": _txt(r.get("COM_ABBRV")),
            "total_fee": fee,
            "listed_date": _txt(r.get("LIST_DD")).replace("/", "-"),
            "index_name": _txt(r.get("ETF_OBJ_IDX_NM")),
            "replication": _txt(r.get("ETF_REPLICA_METHD_TP_CD")),
            "asset_class": _txt(r.get("IDX_ASST_CLSS_NM")),
            "cu_qty": _int(r.get("CU_QTY")),
        }
    return out


def parse_krx_daily_all(data: dict) -> dict[str, dict]:
    """KRX Open API etp/etf_bydd_trd(OutBlock_1) 또는 정보데이터시스템 MDCSTAT04301(output) → {단축코드: 시세·순자산}.

    순자산총액·시가총액은 원 단위. NAV 는 원(소수 둘째 자리).
    """
    out: dict[str, dict] = {}
    for r in _rows(data, "OutBlock_1", "output"):
        code = short_of(_txt(r.get("ISU_SRT_CD"))) or short_of(_txt(r.get("ISU_CD")))
        if not code:
            continue
        out[code] = {
            "code": code,
            "name": _txt(r.get("ISU_NM")) or _txt(r.get("ISU_ABBRV")),
            "base_date": _txt(r.get("BAS_DD")),
            "close": _int(r.get("TDD_CLSPRC")),
            "nav": num(r.get("NAV")),
            "net_assets": _int(r.get("INVSTASST_NETASST_TOTAMT")),
            "listed_shares": _int(r.get("LIST_SHRS")),
            "market_cap": _int(r.get("MKTCAP")),
            "index_name": _txt(r.get("IDX_IND_NM")),
        }
    return out


_CASH_RE = re.compile(r"(현금|원화|예금|예치금|설정현금액|cash|krw|콜론|증거금|미지급|미수)", re.I)
_FUTURE_RE = re.compile(r"(선물|옵션|스왑|swap|future|F\s?\d{6})", re.I)
_BOND_RE = re.compile(r"(국고|통안|국채|회사채|금융채|은행채|공사채|지방채|CD\b|CP\b|전단채|채권)", re.I)


def classify_by_id(code: str) -> str:
    """ISIN 앞자리로 확정되는 자산 종류. 판단할 수 없으면 ''."""
    c = (code or "").strip().upper()
    if c.startswith("KRD") or c in {"CASH00000001", "KRW"}:
        return "CASH"
    if c.startswith("KR4"):
        return "DERIVATIVE"
    if _ISIN_RE.match(c) and c[:3] in {"KR1", "KR2", "KR3", "KR6"}:
        return "BOND"
    if _ISIN_RE.match(c) and not c.startswith("KR"):
        return "FOREIGN"
    return ""


def classify_by_name(name: str) -> str:
    """코드로 찾지 못한 항목을 이름으로 분류(현금·파생·채권). 판단할 수 없으면 ''."""
    if _CASH_RE.search(name or ""):
        return "CASH"
    if _FUTURE_RE.search(name or ""):
        return "DERIVATIVE"
    if _BOND_RE.search(name or ""):
        return "BOND"
    return ""


def parse_krx_pdf(data: dict) -> list[dict]:
    """MDCSTAT05001(ETF PDF) → [{code, name, qty, value, weight}] (비중 %)."""
    out = []
    for r in _rows(data, "output", "OutBlock_1"):
        name = _txt(r.get("COMPST_ISU_NM"))
        code = _txt(r.get("COMPST_ISU_CD"))
        if not name and not code:
            continue
        out.append({
            "code": code,
            "name": name,
            "qty": num(r.get("COMPST_ISU_CU1_SHRS")),
            "value": _int(r.get("VALU_AMT")),
            "amount": _int(r.get("COMPST_AMT")),
            "weight": num(r.get("COMPST_RTO")),
        })
    return out


def _walk(obj, depth: int = 0) -> Iterable[tuple[str, object]]:
    if depth > 5:
        return
    if isinstance(obj, dict):
        for k, v in obj.items():
            yield k, v
            yield from _walk(v, depth + 1)
    elif isinstance(obj, list):
        for v in obj:
            yield from _walk(v, depth + 1)


_NAVER_LIST_KEYS = ("etfTop10MajorConstituentAssets", "majorConstituentAssets", "constituentAssets", "etfMajorConstituent")


def parse_naver_holdings(data: dict) -> list[dict]:
    """네이버 증권 ETF 분석 응답에서 구성종목 상위 목록을 찾는다(키 이름이 바뀌어도 견디도록 넓게 탐색)."""
    target = None
    for k, v in _walk(data):
        if k in _NAVER_LIST_KEYS and isinstance(v, list):
            target = v
            break
    if target is None:
        return []
    out = []
    for r in target:
        if not isinstance(r, dict):
            continue
        name = _txt(r.get("itemName") or r.get("stockName") or r.get("name"))
        code = _txt(r.get("itemCode") or r.get("reutersCode") or r.get("code"))
        w = num(r.get("etfWeight") or r.get("weight") or r.get("ratio"))
        if isinstance(r.get("etfWeight"), str) and str(r.get("etfWeight")).endswith("%"):
            w = num(str(r.get("etfWeight")).rstrip("%"))
        if not name and not code:
            continue
        out.append({"code": code, "name": name, "qty": num(r.get("quantity") or r.get("stockCount")), "value": None, "amount": None, "weight": w})
    return out


def parse_naver_fee(data: dict) -> float | None:
    """네이버 응답의 총보수(연 %) 후보 키."""
    for k, v in _walk(data):
        if k in {"totalFee", "totalExpenseRatio", "totalPay", "etfTotalFee"}:
            f = num(str(v).rstrip("%")) if v is not None else None
            if f is not None:
                return f
    return None


def fill_weights(rows: list[dict]) -> list[dict]:
    """비중이 비어 있으면 평가금액으로 계산한다(평가금액 합 대비 %)."""
    if rows and all(r.get("weight") is None for r in rows):
        total = sum((r.get("value") or 0) for r in rows)
        if total > 0:
            for r in rows:
                r["weight"] = round((r.get("value") or 0) / total * 100, 4)
    return rows


# ───────────────────────── 매칭 보정 ─────────────────────────
_PREF_NAME = re.compile(r"(\d?우[BC]?|우선주?)$")
_SHARE_WORDS = re.compile(r"(보통주|의결권|\(전환\)|\(신형\))")


def norm(name: str) -> str:
    return normalize_name(_SHARE_WORDS.sub("", name or ""))


def base_pref_code(code: str) -> str:
    """우선주 단축코드(끝자리 5·7·9·K·L)를 보통주 코드(끝자리 0)로. 아니면 ''."""
    if len(code) == 6 and code[-1] in "579KL" and code[:5].isalnum():
        return code[:5] + "0"
    return ""


class Matcher:
    """구성종목 → (종류, 상장사 corp_code / ETF 코드) 대조기.

    corp_by_stock: 단축코드 → (corp_code, corp_name) (DART 상장사 색인)
    etfs: 단축코드 → ETF 이름 (키움·KRX 목록)
    """

    def __init__(self, corp_by_stock: dict[str, tuple[str, str]], etfs: dict[str, str]) -> None:
        self.corp_by_stock = corp_by_stock
        self.etfs = etfs
        self.corp_by_name: dict[str, str] = {}
        for code, (_, cname) in corp_by_stock.items():
            self.corp_by_name.setdefault(norm(cname), code)
        self.etf_by_name = {norm(n): c for c, n in etfs.items()}

    def _stock(self, code: str, how: str) -> dict:
        corp_code, corp_name = self.corp_by_stock[code]
        return {"kind": "STOCK", "match": how, "stock_code": code, "corp_code": corp_code, "corp_name": corp_name}

    def match(self, code: str, name: str) -> dict:
        kind = classify_by_id(code)
        if kind:
            return {"kind": kind, "match": "type"}
        short = short_of(code)
        how = "code" if short and short == (code or "").strip().upper() else "isin"
        if short:
            if short in self.etfs:
                return {"kind": "ETF", "match": how, "etf_code": short, "etf_name": self.etfs[short]}
            if short in self.corp_by_stock:
                return self._stock(short, how)
            base = base_pref_code(short)
            if base and base in self.corp_by_stock:
                r = self._stock(base, "pref")
                r["stock_code"] = short  # 시세는 우선주 코드로, 회사 정보는 보통주 회사로
                return r
        n = norm(name)
        if n:
            if n in self.etf_by_name:
                c = self.etf_by_name[n]
                return {"kind": "ETF", "match": "name", "etf_code": c, "etf_name": self.etfs[c]}
            if n in self.corp_by_name:
                return self._stock(self.corp_by_name[n], "name")
            stripped = _PREF_NAME.sub("", n)
            if stripped != n and stripped in self.corp_by_name:
                return self._stock(self.corp_by_name[stripped], "pref")
        kind = classify_by_name(name)
        if kind:
            return {"kind": kind, "match": "type"}
        return {"kind": "UNMATCHED", "match": ""}


def match_holdings(rows: list[dict], matcher: Matcher) -> list[dict]:
    out = []
    for r in rows:
        m = matcher.match(r.get("code", ""), r.get("name", ""))
        out.append({**r, **m})
    out.sort(key=lambda x: -(x.get("weight") or 0))
    return out


def holding_key(h: dict) -> str:
    """ETF 간 비교용 같은 종목 키: 확정된 코드 우선, 없으면 정규화한 이름."""
    if h.get("kind") == "ETF" and h.get("etf_code"):
        return "E:" + h["etf_code"]
    if h.get("kind") == "STOCK" and h.get("stock_code"):
        return "S:" + h["stock_code"]
    s = short_of(h.get("code", ""))
    if s:
        return "S:" + s
    return "N:" + norm(h.get("name", ""))


# ───────────────────────── 관련성 계산 ─────────────────────────

def _weights(holdings: list[dict]) -> dict[str, float]:
    w: dict[str, float] = {}
    for h in holdings:
        if h.get("kind") in {"CASH", "DERIVATIVE"}:
            continue
        k = holding_key(h)
        if k == "N:":
            continue
        w[k] = w.get(k, 0.0) + max(0.0, h.get("weight") or 0.0)
    return w


def overlap(a: list[dict], b: list[dict]) -> dict:
    """두 ETF 구성의 중복도. overlap = Σ min(비중A, 비중B) (현금·파생 제외, %). 공통 종목 목록 포함."""
    wa, wb = _weights(a), _weights(b)
    names: dict[str, str] = {}
    for h in a + b:
        names.setdefault(holding_key(h), h.get("corp_name") or h.get("etf_name") or h.get("name", ""))
    common = sorted(set(wa) & set(wb), key=lambda k: -min(wa[k], wb[k]))
    return {
        "overlap_weight": round(sum(min(wa[k], wb[k]) for k in common), 2),
        "common_count": len(common),
        "count_a": len(wa),
        "count_b": len(wb),
        "common": [{"key": k, "name": names.get(k, k[2:]), "weight_a": round(wa[k], 2), "weight_b": round(wb[k], 2)} for k in common],
    }


def etf_holdings_summary(holdings: list[dict], my_manager: str, manager_lookup) -> dict:
    """편입한 ETF를 자사/타사로 나눠 비중 합계. manager_lookup(code, name) → 운용사."""
    mine_key = manager_key(my_manager)
    own, other = [], []
    for h in holdings:
        if h.get("kind") != "ETF":
            continue
        m = manager_lookup(h["etf_code"], h.get("etf_name", "")) or ""
        row = {"code": h["etf_code"], "name": h.get("etf_name") or h.get("name", ""), "manager": m, "weight": h.get("weight")}
        (own if mine_key and m and manager_key(m) == mine_key else other).append(row)
    tot = lambda rows: round(sum((r["weight"] or 0) for r in rows), 2)  # noqa: E731
    return {"own_weight": tot(own), "other_weight": tot(other), "own": own, "other": other}


def find_cycles(start: str, edges: dict[str, list[str]], max_depth: int = 4, limit: int = 10) -> list[list[str]]:
    """start 에서 출발해 ETF→편입 ETF 간선을 따라 다시 start 로 돌아오는 경로(순환투자)."""
    found: list[list[str]] = []

    def dfs(node: str, path: list[str]) -> None:
        if len(found) >= limit or len(path) > max_depth:
            return
        for nxt in edges.get(node, []):
            if nxt == start:
                found.append(path + [start])
            elif nxt not in path:
                dfs(nxt, path + [nxt])

    dfs(start, [start])
    return found


def compact(holdings: list[dict]) -> dict:
    """교차 분석용 가벼운 형태: {'w': {종목키: 비중}, 'etfs': [편입 ETF 코드]}. 저장소 전체를 메모리에 둘 때 쓴다."""
    return {"w": _weights(holdings), "etfs": [h["etf_code"] for h in holdings if h.get("kind") == "ETF" and h.get("etf_code")]}


def holders_of(target_key: str, store: dict[str, dict], limit: int = 30) -> list[dict]:
    """target_key('E:069500' / 'S:005930')를 편입한 ETF 목록(비중 큰 순). store 는 {코드: compact()}."""
    out = []
    for code, c in store.items():
        w = c["w"].get(target_key, 0.0)
        if w > 0:
            out.append({"code": code, "weight": round(w, 2)})
    out.sort(key=lambda x: -x["weight"])
    return out[:limit]


def most_similar(code: str, store: dict[str, dict], limit: int = 10) -> list[dict]:
    me = store.get(code)
    wa = me["w"] if me else {}
    if not wa:
        return []
    res = []
    for other, c in store.items():
        if other == code:
            continue
        wb = c["w"]
        small, big = (wa, wb) if len(wa) <= len(wb) else (wb, wa)
        ov, n = 0.0, 0
        for k, v in small.items():
            if k in big:
                ov += min(v, big[k])
                n += 1
        if ov > 0:
            res.append({"code": other, "overlap_weight": round(ov, 2), "common_count": n})
    res.sort(key=lambda x: -x["overlap_weight"])
    return res[:limit]
