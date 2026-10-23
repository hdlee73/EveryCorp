# EveryCorp REST API 명세 v1

- Base URL: `https://{서버}/api/v1` (서버 주소는 GitHub Variables `API_BASE_URL`로 앱 빌드에 주입)
- 인증: `/health`, `/app/meta`를 뺀 모든 요청에 헤더 `X-App-Token: {APP_TOKEN}`
- 형식: UTF-8 JSON, 날짜 `YYYY-MM-DD`, 금액은 **원 단위 정수** + 표시용 문자열(`display`)
- 아래 JSON의 숫자는 형식 설명용 **예시 값**이다.

## 공통 오류
```json
{ "error": { "code": "NOT_LISTED", "message": "비상장 법인은 주가 정보를 제공하지 않습니다." } }
```
| HTTP | code | 의미 |
|---|---|---|
| 400 | `INVALID_CORP_CODE` | corp_code 형식 오류(8자리 숫자) |
| 401 | `UNAUTHORIZED` | 앱 토큰 불일치 |
| 404 | `CORP_NOT_FOUND` | DART 기업개황 없음 |
| 404 | `NOT_LISTED` | 비상장사에 주가 요청 |
| 422 | (FastAPI 기본) | 쿼리 파라미터 검증 실패 |
| 502 | `UPSTREAM_ERROR` | DART·KIND·시세 원천 오류(메시지에 `[DART]` 등 출처) |
| 503 | `DART_KEY_MISSING` | 서버 키 미설정 |

---

## 0. 앱 정보 · 업데이트

### `GET /app/meta?current_version=1.0.0`  (인증 불필요)
```json
{
  "app_name": "EveryCorp",
  "app_name_ko": "에브리코프 기업정보",
  "author": { "name": "이현덕", "email": "hdlee73@gmail.com" },
  "repository": "https://github.com/hdlee73/EveryCorp",
  "min_supported_version": "1.0.0",
  "current_version": "1.0.0",
  "update_available": true,
  "force_update": false,
  "latest": {
    "version": "1.1.0",
    "published_at": "2026-11-02T03:00:00Z",
    "notes": "- 공시목록 제목 검색 추가\n- 주가 차트 성능 개선",
    "release_url": "https://github.com/hdlee73/EveryCorp/releases/tag/v1.1.0",
    "apk_url": "https://github.com/hdlee73/EveryCorp/releases/download/v1.1.0/everycorp-v1.1.0.apk",
    "apk_size": 8123456,
    "sha256_url": "https://github.com/hdlee73/EveryCorp/releases/download/v1.1.0/everycorp-v1.1.0.apk.sha256"
  }
}
```
- `force_update=true`: `current_version < min_supported_version` → 앱은 닫을 수 없는 업데이트 팝업.
- 서버가 응답하지 않으면 앱은 GitHub Releases API를 직접 조회(폴백).

### `GET /health`  (인증 불필요)
```json
{ "status": "ok", "corp_index_size": 113245 }
```

---

## 1. 기업 검색 (공통 검색바 자동완성)

### `GET /corps/search?q={검색어}&limit=20&listed_only=false`
- 회사명(괄호·`(주)`·공백 무시), **초성**(`ㅅㅅㅈㅈ`), 종목코드 6자리, corp_code 8자리 지원
- 정렬: 완전일치 > 앞부분일치 > 포함, 상장사 우선, 짧은 이름 우선
- 앱은 입력 후 250ms 디바운스로 호출
```json
{
  "query": "삼성전자",
  "count": 2,
  "items": [
    {
      "corp_code": "00126380",
      "corp_name": "삼성전자",
      "corp_name_eng": "SAMSUNG ELECTRONICS CO,.LTD",
      "stock_code": "005930",
      "listing": "LISTED",
      "badge": "상장"
    },
    {
      "corp_code": "00258801",
      "corp_name": "삼성전자서비스",
      "corp_name_eng": null,
      "stock_code": null,
      "listing": "UNLISTED",
      "badge": "외감"
    }
  ]
}
```
> `badge`: DART 고유번호 목록에는 정기보고서·감사보고서 제출 법인이 들어 있어 비상장은 `외감`으로 표시한다. 정확한 시장구분(KOSPI/KOSDAQ/KONEX)은 `overview.market`.

---

## 2. 탭 1 — 기업개황

### `GET /corps/{corp_code}/overview`
```json
{
  "corp_code": "00126380",
  "corp_name": "삼성전자",
  "corp_name_eng": "SAMSUNG ELECTRONICS CO,.LTD",
  "stock_code": "005930",
  "listing": "LISTED",
  "market": "KOSPI",
  "ceo_name": "전영현, 노태문",
  "industry_code": "264",
  "established": "19690113",
  "fiscal_month": "12",
  "address": "경기도 수원시 영통구 삼성로 129 (매탄동)",
  "homepage": "www.samsung.com/sec",
  "phone": "02-2255-0114",
  "largest_shareholder": {
    "available": true,
    "name": "삼성생명보험㈜",
    "related_count": 15,
    "total_ratio": 20.07,
    "display": "최대주주 삼성생명보험㈜ 외 특수관계인 15명 (합산 지분율 20.1%)",
    "holders": [
      { "name": "삼성생명보험㈜", "relation": "최대주주 본인", "shares": 508157148, "ratio": 8.51 },
      { "name": "삼성물산㈜", "relation": "계열회사", "shares": 298818100, "ratio": 5.01 }
    ],
    "basis": {
      "report_name": "반기보고서 (2026.06)",
      "rcept_no": "20260814000300",
      "bsns_year": "2026",
      "reprt_code": "11012",
      "period_end": "2026-06"
    }
  },
  "financials": {
    "available": true,
    "fs_div": "CFS",
    "fs_label": "연결",
    "revenue_label": "매출액",
    "currency": "KRW",
    "basis": {
      "report_name": "사업보고서 (2025.12)",
      "rcept_no": "20260310000100",
      "bsns_year": "2025",
      "reprt_code": "11011",
      "period_end": "2025-12"
    },
    "balance_sheet": {
      "total_assets":      { "label": "자산총계", "account_nm": "자산총계", "amount": 514531948000000, "display": "514조 5,319억원", "prev_amount": 455905980000000, "prev2_amount": 448424507000000 },
      "total_liabilities": { "label": "부채총계", "account_nm": "부채총계", "amount": 112339878000000, "display": "112조 3,398억원", "prev_amount": 92228115000000, "prev2_amount": 93674903000000 },
      "total_equity":      { "label": "자본총계", "account_nm": "자본총계", "amount": 402192070000000, "display": "402조 1,920억원", "prev_amount": 363677865000000, "prev2_amount": 354749604000000 }
    },
    "income_statement": {
      "revenue":          { "label": "매출액",   "account_nm": "매출액",   "amount": 300870903000000, "display": "300조 8,709억원", "prev_amount": 258935494000000, "prev2_amount": 302231360000000 },
      "operating_income": { "label": "영업이익", "account_nm": "영업이익", "amount": 32725961000000,  "display": "32조 7,259억원",  "prev_amount": 6566976000000,   "prev2_amount": 43376630000000 },
      "net_income":       { "label": "당기순이익", "account_nm": "당기순이익", "amount": 34451351000000, "display": "34조 4,513억원", "prev_amount": 15487100000000, "prev2_amount": 55654077000000 }
    }
  }
}
```

**금융업 예시(영업수익 라벨 자동 변경)** — `financials.income_statement.revenue`
```json
{ "label": "영업수익", "account_nm": "영업수익", "amount": 80123000000000, "display": "80조 1,230억원", "prev_amount": 75000000000000, "prev2_amount": 70000000000000 }
```
`revenue_label`도 `"영업수익"`이 되므로 앱은 이 값을 그대로 라벨로 쓴다.

**외감(비상장) 법인 예시**
```json
{
  "listing": "UNLISTED",
  "market": "OTHER",
  "largest_shareholder": { "available": false, "display": "정기보고서(사업·반기·분기) 제출 대상이 아니어서 최대주주 현황을 제공하지 않습니다.", "holders": [] },
  "financials": { "available": false, "message": "DART 재무정보 API는 정기보고서 제출 법인만 제공합니다. 감사보고서 탭에서 재무제표 원문(PDF)을 확인하세요." }
}
```

---

## 3. 탭 2 — 감사보고서 · 검토보고서

### `GET /corps/{corp_code}/audit-reports?years=3`
```json
{
  "corp_code": "00126380",
  "corp_name": "삼성전자",
  "from": "2023-10-22",
  "to": "2026-10-22",
  "count": 3,
  "partial": false,
  "reports": [
    {
      "id": "20260814000300-10500011",
      "title": "연결반기검토보고서",
      "kind": "REVIEW",
      "consolidated": true,
      "period": "2026-06",
      "period_label": "2026.06 반기",
      "rcept_dt": "20260814",
      "rcp_no": "20260814000300",
      "dcm_no": "10500011",
      "origin": "ATTACHMENT",
      "source_report_name": "반기보고서 (2026.06)",
      "pdf_url": "https://dart.fss.or.kr/pdf/download/pdf.do?rcp_no=20260814000300&dcm_no=10500011",
      "pdf_proxy_path": "/api/v1/reports/pdf?rcp_no=20260814000300&dcm_no=10500011",
      "viewer_url": "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260814000300&dcmNo=10500011",
      "file_name": "삼성전자_2026-06_연결반기검토보고서.pdf"
    },
    {
      "id": "20260310000100-10363871",
      "title": "연결감사보고서",
      "kind": "AUDIT",
      "consolidated": true,
      "period": "2025-12",
      "period_label": "2025.12 결산",
      "rcept_dt": "20260310",
      "rcp_no": "20260310000100",
      "dcm_no": "10363871",
      "origin": "ATTACHMENT",
      "source_report_name": "사업보고서 (2025.12)",
      "pdf_url": "https://dart.fss.or.kr/pdf/download/pdf.do?rcp_no=20260310000100&dcm_no=10363871",
      "pdf_proxy_path": "/api/v1/reports/pdf?rcp_no=20260310000100&dcm_no=10363871",
      "viewer_url": "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260310000100&dcmNo=10363871",
      "file_name": "삼성전자_2025-12_연결감사보고서.pdf"
    },
    {
      "id": "20260303000555-10360001",
      "title": "감사보고서",
      "kind": "AUDIT",
      "consolidated": false,
      "period": "2025-12",
      "period_label": "2025.12 결산",
      "rcept_dt": "20260303",
      "rcp_no": "20260303000555",
      "dcm_no": "10360001",
      "origin": "STANDALONE",
      "source_report_name": "감사보고서 (2025.12)",
      "pdf_url": "https://dart.fss.or.kr/pdf/download/pdf.do?rcp_no=20260303000555&dcm_no=10360001",
      "pdf_proxy_path": "/api/v1/reports/pdf?rcp_no=20260303000555&dcm_no=10360001",
      "viewer_url": "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260303000555&dcmNo=10360001",
      "file_name": "삼성전자_2025-12_감사보고서.pdf"
    }
  ]
}
```
- `kind`: `AUDIT`(감사보고서) | `REVIEW`(반기·분기 검토보고서)
- `origin`: `STANDALONE`(외부감사관련 단독 공시) | `ATTACHMENT`(정기보고서 첨부서류)
- `partial=true`: 일부 뷰어 파싱 실패(목록은 있는 만큼 반환)

**앱 다운로드 절차**: `pdf_url`을 `DownloadManager.Request`로 요청(헤더 `Referer: viewer_url`, `User-Agent` 지정, 저장 위치 `Download/EveryCorp/{file_name}`) → 완료 후 MIME이 PDF가 아니거나 실패하면 `{BASE}{pdf_proxy_path}`(헤더 `X-App-Token`)로 다시 요청 → `ACTION_VIEW`로 열기.

### `GET /reports/pdf?rcp_no={14자리}&dcm_no={문서번호}`
- 응답: `application/pdf` 스트림, `Content-Disposition: attachment`
- PDF가 아니면 `502 UPSTREAM_ERROR`

---

## 4. 탭 3 — 공시목록 (DART + KIND)

### `GET /corps/{corp_code}/disclosures?years=1&source=all&dedupe=false&q=&page=1&size=50`
| 파라미터 | 기본 | 설명 |
|---|---|---|
| `years` | 1 | 1~10 (앱 칩: 1년·3년·5년·10년) |
| `source` | all | `all` · `dart` · `kind` |
| `dedupe` | false | true면 DART와 같은 날·같은 제목인 KIND 항목 숨김 |
| `q` | - | 제목 검색 |
| `page`,`size` | 1, 50 | 무한 스크롤 |

```json
{
  "corp_code": "00126380",
  "from": "2025-10-22",
  "to": "2026-10-22",
  "years": 1,
  "source": "all",
  "kind_supported": true,
  "counts": { "dart": 212, "kind": 187, "total": 399 },
  "page": 1,
  "size": 50,
  "has_next": true,
  "items": [
    {
      "id": "KIND-20261021000812",
      "source": "KIND",
      "date": "2026-10-21",
      "time": "17:32",
      "title": "기업설명회(IR) 개최(안내공시)",
      "submitter": "삼성전자",
      "remark": null,
      "url": "https://kind.krx.co.kr/common/disclsviewer.do?method=search&acptno=20261021000812&docno=&viewerhost=&viewerport=",
      "mobile_url": "https://kind.krx.co.kr/common/disclsviewer.do?method=search&acptno=20261021000812&docno=&viewerhost=&viewerport="
    },
    {
      "id": "DART-20261021800512",
      "source": "DART",
      "date": "2026-10-21",
      "time": null,
      "title": "임원ㆍ주요주주특정증권등소유상황보고서",
      "submitter": "홍길동",
      "remark": null,
      "url": "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261021800512",
      "mobile_url": "https://m.dart.fss.or.kr/html_mdart/MD1007.html?rcpNo=20261021800512"
    }
  ],
  "warnings": []
}
```
- 정렬: 공시일자 desc → 시각 desc(KIND만 시각 있음) → 접수번호 desc
- `remark`(DART `rm`): 유(유가)·코(코스닥)·넥(코넥스)·채(채권)·공(공정위)·연(연결)·정(정정)·철(철회)
- 앱은 항목 클릭 시 `mobile_url`을 Chrome Custom Tabs로 연다.
- KIND 실패 시 DART만 반환하고 `warnings`에 사유를 넣는다. 비상장사는 `kind_supported=false`.

---

## 5. 탭 4 — 주가현황 (상장사 전용)

### `GET /corps/{corp_code}/stock/quote`
```json
{
  "stock_code": "005930",
  "name": "삼성전자",
  "price": 71000,
  "change": -500,
  "change_rate": -0.70,
  "direction": "DOWN",
  "open": 71500,
  "high": 71800,
  "low": 70800,
  "volume": 12345678,
  "week52_high": 88800,
  "week52_low": 49900,
  "per": 12.5,
  "pbr": 1.1,
  "eps": 5677.0,
  "bps": 64000.0,
  "roe": 9.0,
  "source": "KIWOOM",
  "fetched_at": "2026-10-22T10:15:03+09:00"
}
```
- `direction`: `UP`(빨강 ▲) · `DOWN`(파랑 ▼) · `FLAT`(회색 -)
- `source`: `KIWOOM`(키움 REST, 1순위) | `NAVER`(키움 실패·미설정 시) | `KRX`(전일 종가, `delayed:true`). `week52_*`는 키움의 250거래일 최고/최저. `per/pbr/eps/bps/roe`는 키움 응답일 때만. 네이버 응답은 `market_status`, `traded_at`가 있고 `week52_*`가 없음(차트의 `range_52w` 사용)
- 앱은 탭이 화면에 있을 때만 5초 간격 폴링(장 마감 후 60초)
- 비상장: `404 NOT_LISTED`

### `GET /corps/{corp_code}/stock/chart?days=365`
```json
{
  "stock_code": "005930",
  "name": "삼성전자",
  "from": "2025-10-22",
  "to": "2026-10-22",
  "source": "KIWOOM",
  "points": [
    { "date": "2025-10-22", "close": 58900, "high": 59400, "low": 58500, "volume": 15234567 },
    { "date": "2026-10-21", "close": 71500, "high": 72000, "low": 70900, "volume": 11234567 }
  ],
  "range_52w": { "high": 88800, "high_date": "2026-07-15", "low": 49900, "low_date": "2025-11-14" },
  "last_date": "2026-10-21"
}
```
- `source`: `KIWOOM`(ka10081 수정주가 일봉, 1순위) | `DATA_GO_KR`(공공데이터포털, 전 영업일까지) | `NAVER`(폴백)
- 52주 최고/최저는 일별 **장중 고가/저가** 기준. 앱은 현재가가 이 범위를 넘으면 현재가로 갱신해 표시한다.

---

## 5-1. 탭 5 — 채권·단기증권 (미상환 잔액)

### `GET /corps/{corp_code}/debt-securities`
DART 정기보고서의 증권 미상환 잔액(회사채·기업어음증권(CP)·단기사채·신종자본증권·조건부자본증권)을 잔여만기 구간별로 보여준다. 최신 정기보고서부터 최대 4건을 살펴 잔액 표가 있는 가장 최근 보고서를 사용한다.
```json
{
  "corp_code": "00126380",
  "available": true,
  "unit_note": "공시 원문 단위(통상 백만원) 그대로의 숫자입니다. 정확한 단위는 DART 원문에서 확인하세요.",
  "basis": { "report_name": "사업보고서 (2025.12)", "rcept_no": "20260310000100", "bsns_year": "2025", "reprt_code": "11011", "period_end": "2025-12" },
  "sections": [
    {
      "key": "CORP_BOND",
      "title": "회사채",
      "total": 1700,
      "total_display": "1,700",
      "rows": [
        { "label": "공모", "is_total": false, "total": 1500, "issue_limit": null, "remaining_limit": null,
          "buckets": [ { "key": "yy1_below", "label": "1년 이하", "amount": 1000 }, { "key": "yy1_excess_yy2_below", "label": "1년 초과 2년 이하", "amount": 500 } ] },
        { "label": "합계", "is_total": true, "total": 1700, "issue_limit": null, "remaining_limit": null, "buckets": [] }
      ]
    }
  ],
  "missing": ["기업어음증권", "단기사채", "신종자본증권", "조건부자본증권"],
  "viewer_url": "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260310000100"
}
```
- `key`: `CORP_BOND` · `COMMERCIAL_PAPER`(CP) · `SHORT_TERM_BOND`(단기사채, STB) · `HYBRID_CAPITAL` · `CONTINGENT_CAPITAL`
- 잔액은 **보고서 기준일 시점**이다(일 단위 실시간 잔액 아님). 발행 이력이 없으면 `available:false` + `message`.
- 한국예탁결제원의 종목별 일 단위 잔액(공공데이터포털 채권기본정보·채권발행정보)은 활용신청 후 명세를 확인해 `holdings` 필드로 추가할 예정.

## 6. 원천 API 근거
| 기능 | 원천 | 문서 |
|---|---|---|
| 고유번호 | `GET opendart.fss.or.kr/api/corpCode.xml` (zip) | [DART 개발가이드 DS001](https://opendart.fss.or.kr/guide/main.do?apiGrpCd=DS001) |
| 기업개황 | `company.json` | [DS001 기업개황](https://opendart.fss.or.kr/guide/main.do?apiGrpCd=DS001) |
| 공시검색 | `list.json` (`pblntf_ty` A 정기, F 외부감사관련) | [DS001 공시검색](https://opendart.fss.or.kr/guide/main.do?apiGrpCd=DS001) |
| 최대주주 | `hyslrSttus.json` (`reprt_code` 11011/11012/11013/11014) | [DS002 최대주주 현황](https://opendart.fss.or.kr/guide/main.do?apiGrpCd=DS002) |
| 재무제표 | `fnlttSinglAcntAll.json` (`fs_div` CFS/OFS) | [DS003 단일회사 전체 재무제표](https://opendart.fss.or.kr/guide/main.do?apiGrpCd=DS003) |
| 첨부·dcmNo 파싱 | `dart.fss.or.kr/dsaf001/main.do` `<select id="att">`, `node1['dcmNo']` | [OpenDartReader `dart_utils.py`](https://github.com/FinanceData/OpenDartReader) (attach_docs/sub_docs가 같은 구조 사용) |
| KIND 공시 | `POST kind.krx.co.kr/disclosure/details.do` | KIND 상세검색 화면(비공식) |
| 채권·단기증권 잔액 | `cprndNrdmpBlce.json`(회사채), `entrprsBilScritsNrdmpBlce.json`(CP), `srtpdPsndbtNrdmpBlce.json`(단기사채), `newCaplScritsNrdmpBlce.json`, `cndlCaplScritsNrdmpBlce.json` (모두 `corp_code`,`bsns_year`,`reprt_code`) | [DART 개발가이드 DS002](https://opendart.fss.or.kr/guide/main.do?apiGrpCd=DS002), 파라미터는 [OpenDART MCP 도구 설명](https://glama.ai/mcp/servers/gyeongmin100/Open-Dart-Plugin/tools/get_commercial_paper) 참고. **엔드포인트 이름·구간 컬럼명은 기억과 공개 자료 기준이라 실제 키로 확인 필요** |
| 현재가·일봉 | 키움 REST API: `POST /oauth2/token`, `POST /api/dostk/stkinfo`(api-id `ka10001`), `POST /api/dostk/chart`(api-id `ka10081`) | [키움 REST API](https://openapi.kiwoom.com/guide/apiguide), 해설: [ka10001](https://algolab.co.kr/blog/kiwoom-rest-api-stock-info-ka10001-2026), [ka10081](https://algolab.co.kr/blog/kiwoom-rest-api-chart-daily-minute-tick-2026). **공식 명세 접속 불가로 해설 자료 기준 → 키 발급 후 확인** |
| 현재가 폴백 | 네이버 `polling.finance.naver.com` | 비공식 |
| 일봉 | `apis.data.go.kr/1160100/service/GetStockSecuritiesInfoService/getStockPriceInfo` | [공공데이터포털 금융위원회_주식시세정보](https://www.data.go.kr/data/15094808/openapi.do) |


## v1.0.3 추가 사항

### `GET /corps/{corp_code}/shareholders`
최대주주 및 특수관계인 지분 현황(`holders`, `total`)과 최대주주 변동 내역(`changes`). 정기보고서 제출 법인만 제공하며, 그 외에는 `available: false`.

### `GET /corps/{corp_code}/overview`
`financials.trend`(최근 5개년 매출·영업이익·순이익·부채비율)와 `financials.tables`(기간 비교표)가 추가되었다.

### `GET /corps/{corp_code}/audit-reports`
응답에 `opinions`(회계연도별 감사의견·감사인 요약)가 추가되었다.

## v1.0.5 변경 사항

- `GET /corps/{corp_code}/shareholders`: `totals`(주식 종류별 합계) 추가. `total`은 보통주 합계(하위 호환).
- `GET /corps/{corp_code}/audit-reports`: 최상위 `opinions` 제거, 각 `reports[]` 항목에 `auditor`·`opinion` 추가(해당 기간 정기보고서 기준, 없으면 빈 문자열).
- `GET /corps/{corp_code}/overview`: `financials.tables.income`에 최근 3개 연간 열(기존 2개).

## v1.0.6 추가 사항

- `GET /etfs/search?q=&limit=`: 키움 ETF 전체시세(ka40004)를 6시간 캐시해 이름·코드·기초지수로 검색. 항목은 `code,name,index_name,price,change_rate,nav,volume`.
- `GET /etfs/{code}`: 종목정보(ka40002)·일별추이(ka40003)·현재가. `quote`는 주식 현재가와 같은 형식, `daily[]`는 최신순 `date,close,change_rate,volume,nav,disparity,tracking_error`. 추적오차율은 소수점 없는 응답(예 69)을 100으로 나눠 0.69%로 표시한다.
- `GET /etfs/{code}/chart?days=`: 주식 차트와 같은 형식.

## v1.0.7 추가 사항

- `GET /corps/{corp_code}/shareholders`: DART 최대주주 데이터가 없는 법인은 단독 감사보고서 주석의 주주현황 표에서 추출해 `available: true, estimated: true`로 내려준다(추출 실패 시 기존처럼 `available: false`). `overview.largest_shareholder`도 같은 방식이며 `estimated`가 true다.

## 채권 출처 변경(서버만)

- `GET /corps/{corp_code}/debt-securities`: 공공데이터포털 채권기본정보(예탁결제원)만 사용한다. 조회가 비거나 실패하면 DART 정기보고서로 대체하지 않고 `available:false` 와 사유(`message`)를 내려준다.
