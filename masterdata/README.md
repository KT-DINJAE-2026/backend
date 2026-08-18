# 기반정보 DAT 파일

정류장·노선 기반정보 원본 파일을 보관한다. 용량이 커서 git에 포함하지 않으며
(`.gitignore`에서 `masterdata/*`를 제외하고 이 README만 커밋한다), 배포 이미지 빌드 시
이 디렉터리가 Docker 이미지의 `/masterdata`로 복사된다.

## 디렉터리 구조

```
masterdata/
├── STTN_20250401.dat        # 정류장 — 이미지에 포함되는 기준일 세트
├── ROUTE_20250401.dat       # 노선
├── ROUTESTTN_20250401.dat   # 노선별 경유 정류장
└── archive/                 # 인수받은 원본 전체(2025년 4월 한 달치, 약 5.5GB)
    ├── STTN/                #   원본 폴더명 STTN_4월 → 영어로 재명명
    ├── ROUTE/               #   원본 폴더명 ROUTE_4월
    └── ROUTESTTN/           #   원본 폴더명 ROUTESTTN_4월
```

**루트의 세 파일만 배포 이미지에 들어간다.** `archive/`는 인수 원본(`260706_기반정보`)
보관용으로, `.dockerignore`·`.gcloudignore`에서 제외해 이미지와 Cloud Build 업로드에
절대 포함되지 않는다.

기준일을 바꿀 때는 `archive/`에서 같은 날짜의 세 파일을 루트로 복사한다. 서로 다른
날짜 파일을 섞으면 안 된다. 형식은 UTF-8, `|` 구분, 헤더 없음이다(README.md의
"기반정보 적재" 참고). 기준일 2025-04-01은 AI팀 학습 데이터 전처리와 같은 기준이다.

## 어디에 쓰이나

Cloud Run Job `backend-init`이 최초 1회 Cloud SQL 스키마 생성과 기반정보 적재를 수행할 때
`MASTER_STOP_FILE=/masterdata/STTN_20250401.dat` 형태의 환경변수로 참조한다
(`docs/CLOUD_ARCHITECTURE.md` 참고). 평상시 서비스 컨테이너는 이 파일을 읽지 않는다.

로컬 개발 환경에서는 이미지를 거치지 않고 README.md의 "기반정보 적재" 절차대로
로컬 경로를 직접 지정해 적재하면 된다.
