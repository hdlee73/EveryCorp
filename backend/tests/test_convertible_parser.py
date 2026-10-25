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
    assert cv.parse_balance_table(TABLE) == 12_000_000_000


def test_parse_balance_table_sums_rows_and_unit():
    html = TABLE.replace("(단위 : 원, 주)", "(단위 : 백만원)")
    html = html[:html.index("<tr><td>합계")] + "</table>"
    html = html.replace("10,000,000,000", "10,000").replace("7,000,000,000", "7,000").replace("5,000,000,000", "5,000")
    assert cv.parse_balance_table(html) == 12_000 * 1_000_000


def test_parse_balance_table_none_when_unknown():
    assert cv.parse_balance_table("<table><tr><th>a</th></tr><tr><td>1</td></tr></table>") is None
    assert cv.parse_balance_table("<p>해당사항 없음</p>") == 0


def test_parse_decisions_dedupes_corrections():
    rows = [{"bd_tm": "3", "bd_fta": "10,000,000,000", "bd_mtd": "2028년 03월 01일", "pymd": "2023년 03월 01일"},
            {"bd_tm": "3", "bd_fta": "9,000,000,000", "bd_mtd": "2028년 03월 01일", "pymd": "2023년 03월 01일"}]
    out = cv.parse_decisions(rows, "CB")
    assert len(out) == 1 and out[0]["amount"] == 9_000_000_000 and out[0]["maturity"] == "20280301"
