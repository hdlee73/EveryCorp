# EveryCorp (에브리코프 기업정보)

국내 기업(상장법인·비상장 외감법인)의 **기업개황, 감사·검토보고서 PDF, DART·KIND 통합 공시, 실시간 주가**를 한 화면에서 보는 Android 앱과 중계 서버입니다.

- 만든이: 이현덕 (hdlee73@gmail.com)
- 디자인: [FSS Insights](https://github.com/hdlee73/FSS_insights) 디자인 체계 준용

## 진행 단계
| 단계 | 내용 | 상태 |
|---|---|---|
| Step 1 | 아키텍처 · REST API 명세 → [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), [`docs/API_SPEC.md`](docs/API_SPEC.md) | 완료 |
| Step 2 | FastAPI 백엔드 핵심 로직 → [`backend/`](backend/) | 완료 |
| Step 3 | Android 데이터 레이어(Retrofit, DTO, Repository, PDF 다운로드, 업데이트) → [`app/`](app/) | 완료 |
| Step 4 | Android Compose UI(검색 + 5개 탭: 기업개황·감사검토·공시·주가·채권단기증권), 앱 정보, 자동 업데이트 | 완료(CI 확인 중) |

## 내가 해야 할 일
[`docs/SETUP_CHECKLIST.md`](docs/SETUP_CHECKLIST.md): API 키 발급, 서명키 생성, GitHub Secrets 등록, 레포 보호 설정.
서버 배포(Oracle Always Free): [`docs/ORACLE_DEPLOY.md`](docs/ORACLE_DEPLOY.md)

## 백엔드 실행 (로컬)
```bash
cd backend
python -m venv .venv && . .venv/bin/activate
pip install -r requirements-dev.txt
cp .env.example .env   # 값 입력(커밋 금지)
pytest -q              # 외부 API는 모두 모킹 → 키 없이 통과
uvicorn app.main:app --reload --port 8000
# http://localhost:8000/docs 에서 Swagger UI
```

## 키 관리
API 키는 **GitHub Secrets**에만 저장하고 서버 배포 시 환경변수로 주입합니다. 레포에는 `.env.example`만 있습니다. Secrets 목록과 "공개 레포여도 다른 사람이 업데이트를 배포할 수 없게 하는 방법"은 [`docs/ARCHITECTURE.md` 3장](docs/ARCHITECTURE.md#3-보안--키-관리--배포-사용자-기본-요구사항-반영)에 있습니다.

## 데이터 출처
금융감독원 전자공시시스템(DART, Open DART API), 한국거래소 KIND, 네이버 금융, 공공데이터포털(금융위원회_주식시세정보). 투자 판단의 책임은 이용자에게 있습니다.
