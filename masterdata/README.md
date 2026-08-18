# 기반정보 DAT 파일

정류장·노선 기반정보 원본 파일을 보관한다. 용량이 커서 git에 포함하지 않으며
(`.gitignore`에서 `masterdata/*`를 제외하고 이 README만 커밋한다), 배포 이미지 빌드 시
이 디렉터리가 Docker 이미지의 `/masterdata`로 복사된다.

## 필요한 파일

같은 기준일의 세 파일을 이 디렉터리에 그대로 내려받는다. 서로 다른 날짜 파일을 섞으면 안 된다.
형식은 UTF-8, `|` 구분, 헤더 없음이다(README.md의 "기반정보 적재" 참고).

| 파일 (예: 2025-04-01 기준일) | 내용 |
|---|---|
| `STTN_20250401.dat` | 정류장 |
| `ROUTE_20250401.dat` | 노선 |
| `ROUTESTTN_20250401.dat` | 노선별 경유 정류장 |

## 어디에 쓰이나

Cloud Run Job `backend-init`이 최초 1회 Cloud SQL 스키마 생성과 기반정보 적재를 수행할 때
`MASTER_STOP_FILE=/masterdata/STTN_20250401.dat` 형태의 환경변수로 참조한다
(`docs/CLOUD_ARCHITECTURE.md` 참고). 평상시 서비스 컨테이너는 이 파일을 읽지 않는다.

로컬 개발 환경에서는 이미지를 거치지 않고 README.md의 "기반정보 적재" 절차대로
로컬 경로를 직접 지정해 적재하면 된다.
