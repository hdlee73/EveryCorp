# 사용자가 직접 해야 할 일 (EveryCorp)

키·비밀번호는 채팅에 붙여넣지 말고 **GitHub 웹에서 직접** 등록하세요.
등록 위치: 레포 → Settings → Secrets and variables → Actions → *New repository secret* (Variables 탭은 비밀이 아닌 값).

## 1. API 키 발급
| 키 | 발급처 | 필수 | 용도 |
|---|---|---|---|
| `DART_API_KEY` | [Open DART](https://opendart.fss.or.kr) 회원가입 → 인증키 신청 | **필수** | 기업개황·공시·재무·최대주주 |
| `DATA_GO_KR_KEY` | [공공데이터포털](https://www.data.go.kr) → 「금융위원회_주식시세정보」 활용신청 → 마이페이지의 **일반 인증키(Decoding)** | 권장 | 1년 일봉 차트 |
| `KIS_APP_KEY`, `KIS_APP_SECRET` | [KIS Developers](https://apiportal.koreainvestment.com) 가입·계좌 연결 후 앱키 발급 | 권장 | 실시간 현재가(없으면 네이버로 대체) |
| `KRX_AUTH_KEY` | [KRX Open API](https://openapi.krx.co.kr) 가입 → 서비스(주식 일별매매정보 등) 이용 신청·승인 | 선택 | 전일 종가 폴백. **승인 후 받는 명세서를 저에게 알려주세요(경로·헤더 검증용)** |
| 예탁결제원 데이터 | 공공데이터포털에서 「금융위원회_주식발행 공시정보」, 「금융위원회_주식권리일정정보」 활용신청 (키는 `DATA_GO_KR_KEY` 하나로 공용) | 선택 | 발행주식수·자기주식·유통주식수, 배당 등 권리일정(추가 예정) |

| (추가) 채권 | 공공데이터포털에서 「금융위원회_채권기본정보」(15059592), 「금융위원회_채권발행정보」(15043421) 활용신청 | 선택 | 종목별 채권잔액(`DATA_GO_KR_KEY` 공용). 승인 후 **활용가이드/Swagger 명세를 저에게 공유** |

> KIS = 한국투자증권 Open API. 발급: [KIS Developers](https://apiportal.koreainvestment.com)에서 한국투자증권 계좌 연결 후 앱키·앱시크릿 신청. 어려우면 건너뛰어도 네이버로 대체됩니다.

> 공공데이터포털은 서비스마다 **활용신청**이 따로 필요합니다(키는 하나). 승인에 수 시간~1일 걸릴 수 있습니다.

## 2. 앱 서명키 만들기 (한 번만, 분실하면 업데이트 불가 → 안전한 곳에 백업)
```bash
keytool -genkeypair -v -keystore everycorp-release.jks -alias everycorp \
  -keyalg RSA -keysize 2048 -validity 36500
base64 -w0 everycorp-release.jks > everycorp-release.jks.b64   # macOS: base64 -i ... -o ...
```
이 `.jks` 파일은 **절대 레포에 올리지 마세요**(.gitignore에 막아 두었습니다).

## 3. GitHub Secrets 등록 (레포 → Actions secrets)
| 이름 | 값 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `everycorp-release.jks.b64` 파일 내용 전체 |
| `ANDROID_KEYSTORE_PASSWORD` | 키스토어 비밀번호 |
| `ANDROID_KEY_ALIAS` | `everycorp` |
| `ANDROID_KEY_PASSWORD` | 키 비밀번호 |
| `APP_TOKEN` | 임의의 긴 문자열(예: `openssl rand -hex 24`). 서버와 앱이 같은 값을 써야 함 |
| `DART_API_KEY`, `DATA_GO_KR_KEY`, `KIS_APP_KEY`, `KIS_APP_SECRET`, `KRX_AUTH_KEY` | 위 1번에서 발급한 값 |

**Variables 탭**: `API_BASE_URL` = 서버 주소(예: `https://everycorp-api-xxxx.a.run.app`). 서버 배포 후 등록.

## 4. 공개 레포 보호 설정 (다른 사람이 업데이트를 배포하지 못하게)
1. Settings → Branches → `main` 보호 규칙: *Require a pull request before merging*, *Do not allow bypassing*.
2. Settings → Actions → General → *Fork pull request workflows*: **Require approval for all outside collaborators**.
3. Settings → Collaborators: 쓰기 권한은 본인만.
→ 릴리스는 `main` push로만 만들어지고, 서명키는 Secrets에만 있어 포크는 같은 서명의 APK를 만들 수 없습니다. 앱도 설치 전에 서명·체크섬을 한 번 더 검사합니다.

## 5. 서버 배포 (호스팅 확정 필요)
추천은 Google Cloud Run(서울). 선택하시면 GCP 프로젝트 생성·서비스계정 키(Secrets)·배포 워크플로를 안내/작성하겠습니다. 다른 곳(Fly.io, Render, 개인 서버)도 가능합니다.

## 6. 첫 릴리스 순서
1. 위 2~3번 등록 → 서버 배포 → `API_BASE_URL` 등록
2. PR 머지 → `Release APK` 워크플로가 서명 APK를 GitHub Release(`v1.0.0`)로 게시
3. 폰에서 릴리스의 `everycorp-v1.0.0.apk` 설치(출처 불명 앱 허용 필요)
