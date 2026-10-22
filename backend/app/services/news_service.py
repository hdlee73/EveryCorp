"""탭: 뉴스. 기업명을 키워드로 최신 뉴스를 검색한다.

FSS Insights 와 같은 방식: 네이버 검색 뉴스 API(openapi.naver.com/v1/search/news.json, 최신순)를 서버가 호출하고,
네이버 키가 없거나 실패하면 구글 뉴스 RSS로 대체한다. 키는 서버 환경변수에만 둔다.
"""
from __future__ import annotations

import html
import logging
import re
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime
from urllib.parse import quote, urlparse

from ..cache import cached
from ..config import get_settings
from ..errors import ApiError
from ..http import get_client
from .corp_index import corp_index

log = logging.getLogger(__name__)

NAVER_URL = "https://openapi.naver.com/v1/search/news.json"
GOOGLE_URL = "https://news.google.com/rss/search"
KST = timezone(timedelta(hours=9))
_TAG_RE = re.compile(r"<[^>]+>")
_SPACE_RE = re.compile(r"\s+")


def clean_text(s: str) -> str:
    """네이버 응답의 <b>태그와 HTML 엔티티 제거."""
    return _SPACE_RE.sub(" ", html.unescape(_TAG_RE.sub("", s or ""))).strip()


def to_kst_iso(rfc822: str) -> str | None:
    try:
        return parsedate_to_datetime(rfc822).astimezone(KST).strftime("%Y-%m-%dT%H:%M:%S+09:00")
    except (TypeError, ValueError):
        return None


_PUBLISHER_NAMES = {
    "yna.co.kr": "연합뉴스", "yonhapnews.co.kr": "연합뉴스", "biz.chosun.com": "조선비즈", "chosun.com": "조선일보", "joongang.co.kr": "중앙일보",
    "donga.com": "동아일보", "hani.co.kr": "한겨레", "khan.co.kr": "경향신문", "mk.co.kr": "매일경제", "hankyung.com": "한국경제",
    "sedaily.com": "서울경제", "edaily.co.kr": "이데일리", "mt.co.kr": "머니투데이", "fnnews.com": "파이낸셜뉴스",
    "asiae.co.kr": "아시아경제", "newsis.com": "뉴시스", "news1.kr": "뉴스1", "etnews.com": "전자신문",
    "heraldcorp.com": "헤럴드경제", "ajunews.com": "아주경제", "inews24.com": "아이뉴스24", "zdnet.co.kr": "지디넷코리아",
    "sbs.co.kr": "SBS", "kbs.co.kr": "KBS", "mbc.co.kr": "MBC", "ytn.co.kr": "YTN", "jtbc.co.kr": "JTBC",
}


def publisher_from_url(url: str) -> str:
    host = (urlparse(url).hostname or "").lower().removeprefix("www.")
    for dom, name in _PUBLISHER_NAMES.items():
        if host == dom or host.endswith("." + dom):
            return name
    return host


def parse_naver(data: dict) -> list[dict]:
    out = []
    for it in data.get("items", []):
        link = it.get("originallink") or it.get("link") or ""
        out.append({
            "title": clean_text(it.get("title", "")),
            "summary": clean_text(it.get("description", "")),
            "publisher": publisher_from_url(link),
            "published_at": to_kst_iso(it.get("pubDate", "")),
            "url": it.get("link") or link,
        })
    return out


def parse_google_rss(xml_text: str) -> list[dict]:
    root = ET.fromstring(xml_text)
    out = []
    for it in root.iter("item"):
        title = clean_text(it.findtext("title") or "")
        source = clean_text(it.findtext("source") or "")
        if source and title.endswith(" - " + source):   # 구글은 제목 끝에 " - 매체명"을 붙인다
            title = title[: -len(source) - 3].rstrip()
        out.append({
            "title": title,
            "summary": "",
            "publisher": source,
            "published_at": to_kst_iso(it.findtext("pubDate") or ""),
            "url": (it.findtext("link") or "").strip(),
        })
    return out


async def _naver(query: str, limit: int) -> list[dict]:
    s = get_settings()
    r = await get_client().get(
        NAVER_URL, params={"query": query, "display": limit, "start": 1, "sort": "date"},
        headers={"X-Naver-Client-Id": s.naver_client_id, "X-Naver-Client-Secret": s.naver_client_secret},
    )
    if r.status_code != 200:
        raise RuntimeError(f"네이버 뉴스 HTTP {r.status_code}")
    return parse_naver(r.json())


async def _google(query: str, limit: int) -> list[dict]:
    r = await get_client().get(GOOGLE_URL, params={"q": query, "hl": "ko", "gl": "KR", "ceid": "KR:ko"})
    if r.status_code != 200:
        raise RuntimeError(f"구글 뉴스 HTTP {r.status_code}")
    return parse_google_rss(r.text)[:limit]


@cached(ttl=5 * 60)
async def search_news(query: str, limit: int = 30) -> dict:
    s = get_settings()
    source, items, errors = "", [], []
    if s.naver_client_id and s.naver_client_secret:
        try:
            items, source = await _naver(query, limit), "NAVER"
        except Exception as e:  # noqa: BLE001
            log.warning("naver news failed: %s", e)
            errors.append(str(e))
    if not items:
        try:
            items, source = await _google(query, limit), "GOOGLE"
        except Exception as e:  # noqa: BLE001
            log.warning("google news failed: %s", e)
            errors.append(str(e))
    if not items and errors and not source:
        raise ApiError(502, "UPSTREAM_ERROR", "뉴스를 불러오지 못했습니다. 잠시 뒤 다시 시도해 주세요.")
    return {"query": query, "source": source, "count": len(items), "items": items}


async def get_news(corp_code: str, limit: int = 30) -> dict:
    entry = corp_index.get(corp_code)
    name = entry.corp_name if entry else corp_code
    # '㈜삼성전자' 같은 접두·접미 표기는 검색어에서 뺀다.
    query = re.sub(r"㈜|\(주\)|\(유\)|주식회사", "", name).strip() or name
    data = await search_news(query, limit)
    return {"corp_code": corp_code, "corp_name": name, **data}
