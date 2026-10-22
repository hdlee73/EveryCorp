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
    kis_app_key: str = ""           # 한국투자증권 Open API (KIS_APP_KEY)
    kis_app_secret: str = ""        # (KIS_APP_SECRET)
    kis_base_url: str = "https://openapi.koreainvestment.com:9443"

    # --- 앱 ↔ 서버 인증 ---
    # 앱이 X-App-Token 헤더로 보낸다. 비어 있으면(로컬 개발) 검사하지 않는다.
    app_token: str = ""

    # --- 앱 업데이트 정책 ---
    min_supported_version: str = "1.0.0"   # 이보다 낮은 앱은 강제 업데이트
    github_token: str = ""                 # (선택) GitHub API 호출 한도 완화용

    # --- 동작 ---
    corp_index_path: str = "data/corp_index.json"
    http_timeout: float = 15.0
    user_agent: str = (
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0 Mobile Safari/537.36"
    )


@lru_cache
def get_settings() -> Settings:
    return Settings()
