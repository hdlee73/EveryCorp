from app.services import market_service
from app.services.corp_index import CorpEntry


def _html() -> bytes:
    html = (
        "<table><tr><th>회사명</th><th>시장구분</th><th>종목코드</th></tr>"
        "<tr><td>삼성전자</td><td>유가</td><td>005930</td></tr>"
        "<tr><td>에코프로</td><td>코스닥</td><td>086520</td></tr>"
        "<tr><td>코넥스사</td><td>코넥스</td><td>123456</td></tr></table>"
    )
    return html.encode("euc-kr")


def test_parse_kind_corp_list():
    assert market_service.parse_kind_corp_list(_html()) == {"005930": "KOSPI", "086520": "KOSDAQ", "123456": "KONEX"}


def test_badges(monkeypatch):
    monkeypatch.setattr(market_service, "_market", {"005930": "KOSPI", "086520": "KOSDAQ"})
    e = lambda name, stock="": CorpEntry("00000001", name, "", stock, "")  # noqa: E731
    assert market_service.badge_of(e("삼성전자", "005930")) == "유가증권"
    assert market_service.badge_of(e("에코프로", "086520")) == "코스닥"
    assert market_service.badge_of(e("미확인", "999999")) == "상장"
    assert market_service.badge_of(e("가나다")) == "외감"
    assert market_service.badge_of(e("제3차유동화전문")) == "기타"
