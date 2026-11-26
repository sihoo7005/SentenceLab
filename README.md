# SentenceLab

오프라인 영어 문장 학습 안드로이드 앱.

- **순수 Kotlin + Jetpack Compose** (WebView 없음)
- 세트 및 문장 등록 / 편집 / 삭제
- 대량 등록: 영어 문장과 한국어 뜻을 탭(Tab)으로 구분
- 플래시카드, 어순 배열, 영작, 빈칸 채우기, 오답 복습
- 영어 문장 듣기 (Android Text-to-Speech)
- 로그인 없이 기기 내부 JSON 파일에 저장

## 개발 환경

Android Studio, JDK 17, Android SDK 36, Android Gradle Plugin 8.13.2, Gradle 8.13, Kotlin 2.2.20.

`app` 모듈을 Android Studio에서 열고 실행하거나 `./gradlew assembleDebug`로 APK를 빌드합니다.

**상태:** v0.1.0 초기 소스. 실제 APK 빌드 및 기기 테스트는 아직 수행하지 않았습니다.

이 프로젝트는 클래스카드와 무관한 독립 구현입니다.
