# 설치 시 Play 프로텍트 경고 줄이기

마켓 밖(GitHub 릴리스)으로 배포하는 APK에 뜨는 Play 프로텍트 경고와, 정당하게 줄이는 방법을 정리한다(2026-11 기준).
경고를 속이거나 피해 가는 기법(난독화로 탐지 회피, 패키지 이름 바꿔 다시 배포 등)은 쓰지 않는다.

## 1. 앱·빌드 쪽에서 한 일

| 점검 항목 | 상태 |
|---|---|
| 서명 | GitHub Secrets의 고정 릴리스 키로 서명(debug 키 아님). v1.0.0과 v1.12.0 인증서 SHA-256 동일 확인. v1.13.1부터 v2+v3 서명 |
| targetSdk / minSdk | 36 / 26. "앱 호환성 낮음" 경고는 targetSdk가 기기 API보다 2단계 이상 낮을 때만 뜬다 |
| 위험 신호 권한 | 문자(SMS)·알림 접근·접근성 권한 없음. `REQUEST_INSTALL_PACKAGES`(다른 앱 설치 요청)는 v1.13.1에서 제거 |
| 자동 업데이트 | 앱이 APK를 직접 받아 설치하지 않고 브라우저로 릴리스 APK(https만)를 연다. 서명이 다른 APK는 OS가 업데이트를 거부 |
| R8 | 기본 `proguard-android-optimize` 축소만 사용. 추가 난독화 도구 없음 |

## 2. 사용자가 직접 해야 하는 일

### 2.1 Android 개발자 인증 (가장 중요)
- 2026-09-30부터 브라질·인도네시아·싱가포르·태국에서, 2027년부터 전 세계에서 인증된 개발자로 **등록된 앱만** 인증 기기에 일반 방식으로 설치된다. 등록 안 된 앱은 ADB나 "고급 설치 절차"로만 설치된다.
- Play 밖에서만 배포하므로 **Android Developer Console** 계정을 만든다: https://support.google.com/android-developer-console/answer/16604405
  - 일반 배포(Full distribution): 1회 25달러, 본인 이름의 정부 신분증·결제 수단으로 신원 확인. 설치 대수 제한 없음.
  - 제한 배포(Limited distribution): 무료, 신분 확인 없음, 최대 20대 기기, 받는 사람은 "초대 수락" 후 설치.
- 신원 확인 뒤 패키지 이름을 등록한다: `io.github.hdlee73.everycorp`, `io.github.hdlee73.financenewsradar`. 등록은 **릴리스 키로 서명한 APK**를 올려 키 소유를 증명하는 방식이다. 키는 Secrets에 있으므로, 콘솔이 요구하는 파일을 받아 오면 Actions로 서명한 APK를 만들어 준다.

### 2.2 Play 프로텍트 검토 요청(오탐 이의 제기)
- "유해한 앱 차단됨"처럼 앱이 유해 앱(PHA)으로 분류된 경우: https://support.google.com/googleplay/android-developer/contact/protectappeals
- 요청 전 확인: 악성 코드·SDK 없음, 불필요한 권한 없음(위 표). 앱 이름, 패키지 이름, 릴리스 APK 링크, 인증서 SHA-256을 함께 적는다.

## 3. 구조적으로 남는 경고
- **"앱 스캔 권장" / "보안 검사를 위해 앱 보내기"**: Play 프로텍트가 처음 보는 앱이면 뜬다. 공식 안내에 따르면 이의 제기로 없어지지 않는다. 사용자가 "스캔"을 눌러 검사를 통과하면 설치된다. 설치 수가 쌓이고 개발자 인증을 마치면 줄어들 수 있지만, 사라진다는 보장은 없다.
- **"출처를 알 수 없는 앱" 허용**: 브라우저로 APK를 설치하려면 기기 설정에서 해당 브라우저에 설치를 허용해야 한다. 이는 안드로이드 기본 동작이라 앱에서 바꿀 수 없다.

## 출처
- Play 프로텍트 경고 개발자 안내: https://developers.google.com/android/play-protect/warning-dev-guidance
- Android 개발자 인증: https://developer.android.com/developer-verification/guides
- Android Developer Console 시작하기: https://support.google.com/android-developer-console/answer/16604405
