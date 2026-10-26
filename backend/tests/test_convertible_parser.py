from app.parsers import convertible as cv

TABLE = """
<p>(단위 : 원, 주)</p>
<table>
<tr><th rowspan="2">구분</th><th rowspan="2">발행일</th><th rowspan="2">만기일</th><th rowspan="2">권면총액</th>
<th colspan="2">미상환사채</th></tr>
<tr><th>권면총액</th><th>전환대상주식수</th></tr>
<tr><td>제1회 CB</td><td>2023.03.01</td><td>2028.03.01</td><td>10,000,000,000</td><td>7,000,000,000</td><td>100,000</td></tr>
<tr><td>제2회 CB</td><td>2024.03.01</td><td>2029.03.01</td><td>5,000,000,000</td><td>5,000,000,000</td><td>50,000</td></tr>
<tr><td>합계</td><td></td><td></td><td>15,000,000,000</td><td>12,000,000,000</td><td>150,000</td></tr>
</table>
"""


def test_parse_balance_table_uses_unredeemed_column_total():
    assert cv.parse_balance_table(TABLE)["total"] == 12_000_000_000


def test_parse_balance_table_sums_rows_and_unit():
    html = TABLE.replace("(단위 : 원, 주)", "(단위 : 백만원)")
    html = html[:html.index("<tr><td>합계")] + "</table>"
    html = html.replace("10,000,000,000", "10,000").replace("7,000,000,000", "7,000").replace("5,000,000,000", "5,000")
    assert cv.parse_balance_table(html)["total"] == 12_000 * 1_000_000


def test_parse_balance_table_none_when_unknown():
    assert cv.parse_balance_table("<table><tr><th>a</th></tr><tr><td>1</td></tr></table>") is None
    assert cv.parse_balance_table("<p>해당사항 없음</p>")["total"] == 0


def test_parse_decisions_dedupes_corrections():
    rows = [{"bd_tm": "3", "bd_fta": "10,000,000,000", "bd_mtd": "2028년 03월 01일", "pymd": "2023년 03월 01일"},
            {"bd_tm": "3", "bd_fta": "9,000,000,000", "bd_mtd": "2028년 03월 01일", "pymd": "2023년 03월 01일"}]
    out = cv.parse_decisions(rows, "CB")
    assert len(out) == 1 and out[0]["amount"] == 9_000_000_000 and out[0]["maturity"] == "20280301"


KMW = """
<p>7-1-1. 전환사채 발행현황</p>
<table><tr><th>일자</th><th>구분</th><th>수량</th></tr><tr><td>2026년 02월 20일</td><td>83,947</td><td>13,067</td></tr></table>
<p>7-1-2. 미상환 전환사채 발행현황</p>
<p>(기준일 : 2026년 06월 30일) (단위 : 백만원, 주)</p>
<p>미상환 전환사채 발행현황</p>
<table>
<tr><th rowspan="2">종류＼구분</th><th rowspan="2">회차</th><th rowspan="2">발행일</th><th rowspan="2">만기일</th><th rowspan="2">권면(전자등록)총액</th>
<th rowspan="2">전환대상 주식의 종류</th><th rowspan="2">전환청구가능기간</th><th colspan="2">전환조건</th><th colspan="2">미상환사채</th><th rowspan="2">비고</th></tr>
<tr><th>전환비율(%)</th><th>전환가액</th><th>권면(전자등록)총액</th><th>전환가능주식수</th></tr>
<tr><td>10회차 무기명식 무이권부 무보증 사모 전환사채</td><td>10회</td><td>2024년 07월 08일</td><td>2028년 07월 08일</td><td>30,000</td><td>기명식 보통주</td>
<td>2025-07-08 ~ 2028-06-08</td><td>100</td><td>13,577</td><td>700</td><td>51,557</td><td>-</td></tr>
<tr><td>합 계</td><td>-</td><td>-</td><td>-</td><td>30,000</td><td>-</td><td>-</td><td>-</td><td>13,577</td><td>700</td><td>51,557</td><td>-</td></tr>
</table>
<p>7-1-3. 미상환 신주인수권부사채 발행현황</p><p>- 해당사항 없음.</p>
<p>7-1-4. 미상환 전환형 조건부자본증권 등 발행현황</p><p>- 해당사항 없음.</p>
"""


def test_parse_report_node_kmw_layout():
    # 2026-10 케이엠더블유 반기보고서 화면 구조(머리글 2줄·병합, 합 계 행, 단위 백만원)
    out = cv.parse_report_node(KMW)
    assert out["CB"]["total"] == 700 * 1_000_000 and out["BW"]["total"] == 0 and out["BW"]["rows"] == []
    assert out["CB"]["base_date"] == "20260630"
    assert out["CB"]["rows"] == [{"name": "10회차 무기명식 무이권부 무보증 사모 전환사채", "tm": "10회", "issued": "20240708",
                                  "maturity": "20280708", "amount": 700 * 1_000_000}]


def test_parse_report_node_without_headings():
    assert cv.parse_report_node("<p>다른 내용</p>") == {"CB": None, "BW": None}


def test_rescale_undoes_wrong_unit():
    # 발행 당시 15억(원), 미상환 1.5억(원)인데 억원 단위로 곱해 1.5경이 된 경우
    assert cv.rescale(150_000_000 * 100_000_000, 1_500_000_000) == 150_000_000
    assert cv.rescale(700 * 1_000_000, 3_000_000_000) == 700 * 1_000_000  # 정상 값은 그대로
    assert cv.rescale(10**17, None) <= 10**13  # 기준이 없어도 10조 초과면 되돌린다
    assert cv.rescale(4_750 * 100_000_000, None) == 4_750 * 100_000_000


def test_unit_uses_nearest_notation():
    html = """<p>(단위 : 억원)</p><p>다른 표 설명</p><p>미상환 전환사채 발행현황 (단위 : 원, 주)</p>
    <table><tr><th>종류</th><th>미상환사채 권면(전자등록)총액</th></tr>
    <tr><td>1회 CB</td><td>150,000,000</td></tr><tr><td>합계</td><td>150,000,000</td></tr></table>"""
    assert cv.parse_balance_table(html)["total"] == 150_000_000
