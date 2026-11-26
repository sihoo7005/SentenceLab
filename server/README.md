# Oracle 서버 API

Google Sign-In 토큰을 검증하고 사용자별 학습 내용을 저장하는 Python API입니다.
FastAPI, Uvicorn, Google Auth, SQLite를 사용합니다.

## 환경

Oracle 인스턴스: RAM 약 500MiB. 운영 중에는 Uvicorn 작업자 1개를 권장합니다.
Python 3.10 이상, Python venv, 로컬 SQLite3 필요.

설치 예시(실행 전에 서버 운영자 승인 필요):
1. 서버 전용 디렉터리를 생성하고 server 하위 소스 파일을 복사합니다.
2. python3 -m venv .venv
3. .venv/bin/python -m pip install -r requirements.txt
4. 환경 변수 GOOGLE_WEB_CLIENT_ID에 Google Cloud Web OAuth 클라이언트 ID를 설정합니다.
5. 환경 변수 SENTENCELAB_DB에 /home/opc/sentencelab-data/sentencelab.sqlite3 를 설정합니다.
6. .venv/bin/uvicorn app:app --host 127.0.0.1 --port 8765 --workers 1
7. 별도 HTTPS 역방향 프록시 또는 Cloudflare Tunnel로만 외부 연결을 허용합니다.

DB 파일은 애플리케이션 첫 실행 때 자동 생성됩니다.
이 서버의 기존 80, 443 포트 및 터널과 충돌하지 않는지 먼저 확인하세요.
SQLite 데이터베이스 파일을 GitHub에 커밋하거나 인터넷에 노출하면 안 됩니다.

## API

GET /health: 상태 확인
POST /v1/auth/challenge: 일회용 nonce 생성
POST /v1/auth/google: Google ID 토큰 검증 후 앱 세션 발급
POST /v1/auth/logout: 앱 세션 종료
GET /v1/snapshot: 개인별 세트와 문장 조회
POST /v1/sets/copy: 지정된 계정만 자신의 전체 세트를 기존 로그인 계정으로 복사 (Bearer 로그인 필요)
PUT /v1/snapshot: revision 일치 시 개인별 데이터 덮어쓰기
DELETE /v1/account: 해당 계정의 서버 데이터 삭제

DB 테이블: users, sessions, login_challenges, sets, sentences
Google 이메일 및 비밀번호를 데이터베이스에 저장하지 않습니다.
앱 세션 토큰은 SHA-256 해시로 저장합니다.
동기화 중 다른 기기에서 업데이트됐다면 409 Conflict를 반환합니다.

개인정보 보호를 위해 접근 속도 제한, HTTPS, 적절한 방화벽, 백업, 개인정보처리방침,
계정 삭제 및 데이터 보존 정책을 운영 전에 확인해야 합니다.
SQLite WAL을 사용하므로 일관성 있는 백업에는 SQLite Backup API를 사용하세요.

테스트: python -m pip install -r requirements.txt pytest httpx
이후 server 폴더에서 python -m pytest -q 를 실행합니다.

아직 Oracle 서버 배포나 실제 데이터베이스 생성은 수행하지 않았습니다.
