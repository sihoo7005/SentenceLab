# SentenceLab

네이티브 Kotlin + Jetpack Compose 기반 영어 문장 학습 안드로이드 앱입니다.
WebView 없이 작동하며 기존 클래스카드 서비스와는 무관한 독립 프로젝트입니다.

## v0.5.0

- 문장을 한 번 끝내면 **✓ 학습 완료** 표시, 모든 일반 학습 유형에서 자동 제외
- 세트의 문장 목록에 **학습** 버튼 추가. 완료 문장도 직접 선택해 암기·어순 배열·영작·빈칸 중 원하는 유형으로 재학습 가능
- 오답 복습은 명시적으로 선택했을 때 완료 문장도 포함 가능
- 완료 상태는 기기 로컬 저장과 Oracle 서버의 SQLite 데이터베이스에 보존되고 **자동 동기화**에 포함
- 다른 기기와 동기화해도 완료 상태는 유지되도록 3방향 병합, 기존 데이터 마이그레이션 및 이전 앱과 서버 호환성 처리
- 원래의 8단어씩 어순 배열 및 단어별 즉시 채점은 유지

## v0.4.0

- **자동 동기화:** Google 로그인 후 앱 실행·재개·로컬 변경 시 자동 동기화하며, 앱 사용 중 60초 간격으로 서버 변경 확인
- 기존 문장과 학습 기록은 **3방향 병합**으로 비교하며, 삭제도 마지막 동기화본과 대조해 적용
- 서버 버전 충돌(HTTP 409)이 있으면 최신 상태를 다시 받아 최대 4회 재시도하며, 오프라인 오류 때는 기기 데이터를 유지
- 다른 Google 계정으로 전환했을 때 개인 데이터를 실수로 업로드하지 않도록 이전 계정 ID를 확인
- 홈의 클라우드 패널에서 동기화 상태 표시, 필요하면 **지금 동기화** 버튼으로 즉시 재시도
- **어순 배열:** 한 단어를 누를 때마다 바로 채점하며, 틀린 단어는 선택되지 않고 다시 시도
- **8단어 이상** 문장은 8단어씩 연속된 구간으로 나누며(나머지는 마지막 구간), 정답 확인 버튼 없이 다음 구간으로 자동 이동
- 전체 문장 도중 틀린 선택이 있었다면 복습 대상으로 기록

## v0.3.0

- 내 세트에서 **Drive / PDF 가져오기**: Android 파일 선택기에서 Google Drive의 Google Docs(가상 문서) 또는 PDF 파일 선택
- Google Docs는 제공자가 지원하는 텍스트/PDF 내보내기 형식으로 읽으며, 로그인 비밀번호나 Drive 권한을 서버에 전달하지 않음
- 텍스트 PDF 추출은 기기에서 PDFBox-Android를 사용. 이미지 기반 스캔 PDF의 OCR은 아직 지원하지 않음
- 파일 내용의 영어 문장을 문장별로 나누고 미리보기 확인 후 **현재 내 세트**에 추가
- 중복 문장 제외, 최대 파일 크기 20MiB, 한 문서 최대 2500문장
- 한글 해석이 없는 문장도 등록 및 서버 백업 가능. 해석 없는 문장은 영작 모드에서 제외
- v0.4.0부터는 로그인 상태에서 문서 가져오기 결과도 자동 동기화

## v0.2.0

- 문장 세트 등록, 수정, 삭제 및 탭 구분 대량 가져오기
- 암기, 단어/구 단위 어순 배열, 영작, 빈칸 채우기, 오답 복습
- 음성 듣기, 오프라인 저장
- Google 로그인 및 Oracle 클라우드 수동 백업/복원(설정 필요)
- 서버 데이터 revision을 확인해 다른 기기 변경 시 충돌 감지

Android 프로젝트 빌드: ./gradlew assembleDebug
APK 경로: app/build/outputs/apk/debug/app-debug.apk
GitHub Actions: .github/workflows/android.yml

필요 환경: JDK 17, Gradle 8.13, Android Gradle Plugin 8.13.2,
Kotlin 2.2.20, compileSdk 36, minSdk 26.

## 구글 로그인 설정

Google Cloud에서 Web 유형 OAuth 클라이언트와 Android 유형 클라이언트를 같은 프로젝트에 생성하세요.
Web 클라이언트 ID는 토큰 발급/서버 검증에 사용합니다.
Android 클라이언트에는 패키지 com.sentencelab.app과 APK 서명 키의 SHA-1을 등록해야 합니다.

GitHub 저장소 Settings > Secrets and variables > Actions > Variables에 등록:
- GOOGLE_WEB_CLIENT_ID: Web 유형 클라이언트 ID
- API_BASE_URL: 서비스 API의 HTTPS 주소 (예: https://api.example.com)

Oracle API 서버에도 같은 GOOGLE_WEB_CLIENT_ID 환경 변수가 필요합니다.
값을 설정한 뒤 APK를 새로 빌드해야 합니다.

**중요:** GitHub-hosted runner가 매번 임시 디버그 인증서로 서명하면
실제로 APK가 생성되더라도 Google 로그인이 실패할 수 있습니다.
고정된 서명 키를 별도로 안전하게 관리해야 합니다. 비밀 키를 GitHub 코드에 올리지 마세요.

## 클라우드 데이터

로그인 없이도 로컬 학습을 계속 사용할 수 있습니다.
v0.2.0의 클라우드 기능은 실시간 자동 동기화가 아닌 **수동 백업/복원**입니다.
복원 시 현재 기기 데이터를 서버 데이터로 교체하기 전에 확인을 받으며,
서버 revision 충돌 시 자동으로 덮어쓰지 않습니다.

서버 구성, 저장 구조 및 실행 요구사항: server/README.md

현재 Oracle 인스턴스에는 서버를 배포하지 않았으며 데이터베이스도 아직 생성하지 않았습니다.

## Android 앱 고정 서명 (Google 인증용)

고정된 Android 서명 키를 Oracle의 전용 디렉터리에 한 번 생성했습니다.
개인 키 파일과 비밀번호는 저장소에 올리지 않습니다.

- keystore: `/home/opc/sentencelab-signing/sentencelab-signing.p12`
- password: `/home/opc/sentencelab-signing/sentencelab-signing.password`
- key alias: `sentencelab`
- Google Android SHA-1: `58:A3:D2:86:29:23:01:87:73:EB:12:54:0C:72:8A:8E:21:A1:76:84`

GitHub Actions Secrets 설정에 다음 두 항목을 추가해야 고정 서명에 적용됩니다.

- `SENTENCELAB_KEYSTORE_BASE64`: p12 파일을 Base64 인코딩한 문자열
- `SENTENCELAB_KEYSTORE_PASSWORD`: password 파일의 한 줄 비밀번호

기기에서 SSH 접속할 수 있다면 비밀값을 채팅에 붙여넣지 않고 GitHub CLI로 직접 설정할 수 있습니다.
설정 전에는 GitHub Actions에서 일반 임시 디버그 키로 서명합니다.
향후 APK 업데이트에 필요한 개인 키이므로 외부 노출을 피하고 안전하게 백업하세요.

## 등록된 공개 Google OAuth 웹 클라이언트 ID

227216295282-3i89nkjf47795541qdi6u5k44tlmro4k.apps.googleusercontent.com

이 값은 공개 ID이며 비밀번호나 클라이언트 보안 비밀번호가 아닙니다.
Android APK 빌드에 반영됐고, Oracle에서는 `server/run_local.py`가 별도의
`public-config.json` 파일을 읽어 서버 프로세스에 적용합니다.

실제 로그인에는 Android OAuth 클라이언트의 서명 SHA-1 등록과 유효한 HTTPS API 주소가
추가로 필요합니다. GitHub Actions Variables의 `API_BASE_URL`을 설정하고
고정 서명 Secrets를 등록해야 합니다.

### Android OAuth 클라이언트 식별

Android 유형 클라이언트 ID: `227216295282-m8plkfc50dj35reb9ruhklaec6uaucf1.apps.googleusercontent.com` (Google Cloud Console에서 APK 패키지와 서명 지문을 등록하는 용도).

앱에서 Google ID 토큰을 요청하고 서버가 audience를 확인하는 데는 **웹 유형** 클라이언트 ID `227216295282-3i89nkjf47795541qdi6u5k44tlmro4k.apps.googleusercontent.com`를 사용합니다.
