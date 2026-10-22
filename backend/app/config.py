"""환경 설정. 모든 키는 환경변수(배포 시 GitHub Secrets → 서버 환경변수)로만 주입한다. 코드·레포에 키를 넣지 않는다."""
from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict

APP_NAME = "EveryCorp"
APP_NAME_KO = "에브리코프 기업정보"
AUTHOR_NAME = "이현덕"
AUTHOR_EMAIL = "hdlee73@gmail.com"
GITHUB_REPO = "hdlee73/EveryCorp"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # --- 외부 API 키 (GitHub Secrets) ---
    dart_api_key: str = ""          # Open DART 인증키 (DART_API_KEY)
    data_go_kr_key: str = ""        # 공공데이터포털 서비스키, 디코딩 키 (DATA_GO_KR_KEY)
    kiwoom_app_key: str = ""        # 키움증권 REST API 앱키 (KIWOOM_APP_KEY)
    kiwoom_secret_key: str = ""     # 키움증권 REST API 시크릿키 (KIWOOM_SECRET_KEY)
    # 실전 https://api.kiwoom.com / 모의투자 https://mockapi.kiwoom.com (실전 키를 모의 서버에 쓰면 8031)
    kiwoom_base_url: str = "https://api.kiwoom.com"

    krx_auth_key: str = ""          # KRX Open API 인증키 (KRX_AUTH_KEY) — 전일(T-1) 종가 기반
    krx_base_url: str = "https://data-dbg.krx.co.kr/svc/apis"

    # --- 앱 ↔ 서버 인증 ---
    # 앱이 X-App-Token 헤더로 보낸다. 비어 있으면(로컬 개발) 검사하지 않는다.
    app_token: str = ""

    # --- 앱 업데이트 정책 ---
    min_supported_version: str = "1.0.0"   # 이보다 낮은 앱은 강제 업데이트
    github_token: str = ""                 # (선택) GitHub API 호출 한도 완화용

    # KIND(kind.krx.co.kr)는 해외·클라우드 데이터센터 IP를 403으로 막는다. 한국 IP 서버라면 true로 켜면 서버에서 수집한다.
    # 기본(false)에서는 앱이 폰(한국 IP)에서 KIND를 직접 불러온다.
    kind_from_server: bool = False

    # --- 동작 ---
    corp_index_path: str = "data/corp_index.json"
    http_timeout: float = 15.0
    # 데스크톱 UA: DART는 모바일 UA에게 모바일 뷰어(첨부 목록·PDF 버튼이 없는 페이지)를 돌려준다.
    user_agent: str = (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0.0.0 Safari/537.36"
    )


@lru_cache
def get_settings() -> Settings:
    return Settings()
