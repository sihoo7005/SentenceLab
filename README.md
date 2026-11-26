# SentenceLab

영어 문장 학습을 위한 네이티브 Kotlin + Jetpack Compose 안드로이드 앱입니다.
WebView, 서버, 로그인, 광고 및 유료 기능이 없습니다.
클래스카드와 무관한 독립 구현입니다.

## v0.1.0 기능

- 세트 생성 및 삭제, 문장 등록·수정·삭제
- 대량 등록: 한 줄에 영어 문장 + Tab + 한국어 뜻, 또는 영어 문장|||한국어 뜻
- 암기: 한국어를 보고 원문 공개, 기억 여부 기록
- 어순 배열: 단어 또는 직접 지정한 구 단위로 문장 재구성
- 영작: 영어 문장 전체를 직접 입력
- 빈칸: 영어 단어 하나를 찾아 입력
- 오답 복습: 마지막 채점이 틀린 문장을 다시 학습
- 영어 듣기: Android 내장 Text-to-Speech 엔진
- 학습 데이터: 앱 전용 저장소의 JSON 파일, 오프라인 동작

## 개발 및 빌드

필요한 환경: JDK 17, Android SDK 36, Android Gradle Plugin 8.13.2,
Gradle 8.13, Kotlin 2.2.20, Compose BOM 2026.09.00.
최소 Android 8.0 (API 26).

Android Studio에서 저장소 루트 디렉터리를 프로젝트로 열어 Gradle 동기화를 진행합니다.

디버그 APK 빌드:

    ./gradlew assembleDebug

Windows에서는 다음 명령을 사용합니다.

    gradlew.bat assembleDebug

**Gradle 부트스트랩:** 바이너리 Wrapper JAR은 저장소에 포함하지 않았습니다.
첫 명령 실행 시 공식 Gradle 서버에서 버전 8.13의 Wrapper JAR을 내려받아
공식 SHA-256 체크섬을 확인한 뒤 실행합니다. 첫 실행에는 인터넷이 필요합니다.
Gradle 배포본의 SHA-256도 확인합니다.

생성 파일의 기본 위치: app/build/outputs/apk/debug/app-debug.apk

## 문장 조각 설정 예시

영어: The earlier this assessment occurs, the more quickly resources can be channelled.

어순 조각: The earlier | this assessment occurs, | the more quickly | resources can be channelled.

조각을 합쳤을 때 영어 원문과 같아야 저장됩니다.

## 데이터와 검증 상태

문장과 학습 기록은 앱 전용 저장소에 보관되며 인터넷 권한이 없습니다.
안드로이드 자동 백업도 껐습니다. 앱을 삭제하면 학습 기록이 삭제됩니다.
클라우드 동기화, 데이터 파일 내보내기, 가져오기는 아직 제공하지 않습니다.

단위 테스트: ./gradlew testDebugUnitTest

**APK 빌드 및 실기기 실행 검증은 아직 완료하지 않았습니다.**
