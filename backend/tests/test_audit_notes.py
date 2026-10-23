from app.parsers.audit_notes import extract_holders, html_to_text
from app.parsers.dart_viewer import parse_nodes

REAL = ("1. 일반사항 라이프자산운용주식회사로 변경하였습니다. 당기말 현재 회사의 자본금은 2,600,000천원이며, 주주현황은 다음과 같습니다. "
        "주 주 명 주 식 수 지 분 율 (주)보이저홀딩스 26,000,000 주 100.00% 합 &amp;nbsp;계 26,000,000 주 100.00% (2) 종속기업 현황 LIFE Korea ...")


def test_single_holder_from_real_text():
    r = extract_holders(html_to_text(REAL))
    assert r == {"holders": [{"name": "(주)보이저홀딩스", "shares": 26_000_000, "ratio": 100.0}], "total_ratio": 100.0}


def test_multiple_holders_sorted_as_given_and_total():
    t = "주주현황 주 주 명 주 식 수 지 분 율 홍길동 600,000 주 60.00% 김철수 300,000 주 30.00% (주)벤처 100,000 주 10.00% 합 계 1,000,000 주 100.00% 3. 기타"
    r = extract_holders(t)
    assert [h["name"] for h in r["holders"]] == ["홍길동", "김철수", "(주)벤처"]
    assert r["total_ratio"] == 100.0


def test_no_table_returns_none():
    assert extract_holders("주주현황은 없습니다") is None


def test_parse_nodes_groups_by_text():
    html = ("node1['text'] = \"감사보고서\"; node1['id'] = \"1\"; node1['rcpNo'] = \"20260406000001\"; node1['dcmNo'] = \"111\"; "
            "node2['text'] = \"주석\"; node2['id'] = \"4\"; node2['rcpNo'] = \"20260406000001\"; node2['dcmNo'] = \"111\"; node2['eleId'] = \"4\"; "
            "node2['offset'] = \"10\"; node2['length'] = \"99\"; node2['dtd'] = \"dart4.xsd\";")
    nodes = parse_nodes(html)
    assert [n["text"] for n in nodes] == ["감사보고서", "주석"]
    assert nodes[1]["eleId"] == "4" and nodes[1]["length"] == "99"
