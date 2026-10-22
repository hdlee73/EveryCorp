import httpx
import respx

from app.cache import cache
from app.config import get_settings
from app.services import news_service as ns


def test_clean_and_publisher():
    assert ns.clean_text("<b>삼성전자</b> &quot;상승&quot;  마감") == '삼성전자 "상승" 마감'
    assert ns.publisher_from_url("https://www.hankyung.com/article/1") == "한국경제"
    assert ns.publisher_from_url("https://biz.chosun.com/x") == "조선비즈"
    assert ns.publisher_from_url("https://unknown.example.com/a") == "unknown.example.com"
    assert ns.to_kst_iso("Thu, 22 Oct 2026 03:00:00 +0000") == "2026-10-22T12:00:00+09:00"


def test_parse_naver():
    items = ns.parse_naver({"items": [{
        "title": "<b>삼성전자</b> 실적", "originallink": "https://www.mk.co.kr/n/1", "link": "https://n.news.naver.com/1",
        "description": "요약 &amp; 내용", "pubDate": "Thu, 22 Oct 2026 03:00:00 +0900"}]})
    assert items[0] == {"title": "삼성전자 실적", "summary": "요약 & 내용", "publisher": "매일경제",
                        "published_at": "2026-10-22T03:00:00+09:00", "url": "https://n.news.naver.com/1"}


def test_parse_google_rss_strips_source_suffix():
    xml = """<rss><channel><item><title>삼성전자 신제품 공개 - 연합뉴스</title><link>https://news.google.com/a</link>
    <pubDate>Thu, 22 Oct 2026 03:00:00 GMT</pubDate><source url="https://www.yna.co.kr">연합뉴스</source></item></channel></rss>"""
    it = ns.parse_google_rss(xml)[0]
    assert it["title"] == "삼성전자 신제품 공개" and it["publisher"] == "연합뉴스" and it["published_at"].startswith("2026-10-22T12:00")


@respx.mock
def test_falls_back_to_google_without_naver_keys(monkeypatch):
    monkeypatch.setenv("NAVER_CLIENT_ID", "")
    get_settings.cache_clear()
    cache.clear()
    respx.get(ns.GOOGLE_URL).mock(return_value=httpx.Response(200, text="<rss><channel><item><title>가 - 나</title><link>u</link></item></channel></rss>"))
    import asyncio
    data = asyncio.run(ns.search_news("삼성전자"))
    assert data["source"] == "GOOGLE" and data["count"] == 1
