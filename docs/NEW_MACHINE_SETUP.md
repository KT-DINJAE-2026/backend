# 새 노트북에서 이어서 작업하기

다른 컴퓨터에서 로컬 실험(테스트·실모델 추론)과 GCP 배포 작업을 그대로 이어가기 위한 설명서다.
같은 내용이 이동용 zip(`backend-portable-assets-*.zip`)의 `README_SETUP.md`로도 들어간다.

## 0. 무엇이 어디에 있나

| 구분 | 내용 | 가져오는 방법 |
|---|---|---|
| **git에 있음** | 코드, Dockerfile, `.gcloudignore`, 모든 문서(`docs/`, README) | `git clone` |
| **git에 없음 → zip** | PMML 모델 2개 + golden test + 매핑 JSON (`models/`, 약 259MB) | zip의 `models/` |
| **git에 없음 → zip** | 기반정보 DAT 기준일 세트 3개 (`masterdata/`, 약 187MB) | zip의 `masterdata/` |
| **git에 없음 → zip** | `.env` (공공데이터포털 인증키 — **시크릿**) | zip 루트 |
| **git에 없음 → zip** | `HANDOFF.md` (개인 인수인계 메모) | zip 루트 |
| **git에 없음 → zip** | Claude Code 메모리 4개 (`claude-memory/`) | 6장 참고 |
| **GCP에 있음** | Cloud Run·Cloud SQL·Secret·이미지 등 전부 | 옮길 것 없음 — 같은 구글 계정으로 로그인만 |
| **zip에 안 넣음** | `masterdata/archive/` (한 달치 원본 5.5GB) | 기준일 교체가 필요할 때만 원본 공유 경로에서 별도 수령 |

핵심: **클라우드 자원은 프로젝트 `kt-dinjae`에 그대로 있으므로, 새 노트북에는 "로컬 자산 + 도구 + 로그인"만 필요하다.**

## 1. 도구 설치

| 도구 | 용도 | macOS | Windows |
|---|---|---|---|
| Git | 저장소 | 기본 포함 / `brew install git` | git-scm.com |
| JDK 21 | 빌드·테스트 | `brew install --cask temurin@21` | Adoptium Temurin 21 설치 |
| Docker Desktop | 로컬 MySQL(compose)·이미지 검증 | docker.com | docker.com (WSL2 필요) |
| gcloud CLI | GCP 배포·조회 | `brew install --cask google-cloud-sdk` | cloud.google.com/sdk 설치기 |
| gh CLI | PR 생성·조회 | `brew install gh` | github.com/cli/cli |
| Postman (선택) | API 수동 테스트 | — | — |

Gradle은 설치하지 않는다(래퍼 `./gradlew` / `gradlew.bat`가 자동 다운로드). Windows는 저장소
경로에 한글이 있으면 테스트가 깨질 수 있다 — README "실행 방법"의 `-PcustomBuildDir` 우회 참고.

## 2. 저장소와 자산 배치

```bash
git clone https://github.com/KT-DINJAE-2026/backend.git
cd backend
# zip을 풀어서 아래 위치에 그대로 복사
#   zip/models/*        → backend/models/
#   zip/masterdata/*    → backend/masterdata/
#   zip/.env            → backend/.env
#   zip/HANDOFF.md      → backend/HANDOFF.md   (선택)
```

배치 후 확인 — 이 6개 파일이 있어야 한다:

```bash
ls models/model_a.pmml models/model_b.pmml models/golden_test_samples.json \
   masterdata/STTN_20250401.dat masterdata/ROUTE_20250401.dat masterdata/ROUTESTTN_20250401.dat
```

`models/*`, `masterdata/*`, `.env`, `*HANDOFF*`는 `.gitignore`에 있어 실수로 커밋되지 않는다.

## 3. 로컬 검증 (여기까지 되면 실험 환경 완성)

```bash
./gradlew clean test
```

- 기대: **96건 통과, 스킵 0**. 모델 파일이 있으면 실모델 스모크 테스트와 golden test 20건 대조
  (`GoldenModelRegressionTests`)가 자동 활성화된다. 스킵이 생기면 `models/` 배치를 다시 확인.
- 테스트 JVM 힙 4g는 `build.gradle`에 이미 설정돼 있다(최종 PMML 파싱용). 메모리 8GB 이하
  노트북이면 다른 앱을 닫고 돌린다.
- 앱을 띄워보려면 Docker Desktop 실행 후 `./gradlew bootRun` (compose가 MySQL 자동 기동).
  `.env`의 TOPIS 키가 있어야 여정 예측 API가 동작한다. 기반정보 적재는 README "기반정보 적재".

## 4. GCP 이어서 작업하기

서비스 계정 키 파일 같은 것은 필요 없다. **같은 구글 계정(kimchanjoong54@gmail.com)** 으로
로그인하면 끝이다.

```bash
gcloud auth login                     # 브라우저 로그인
gcloud config set project kt-dinjae
gcloud auth list                      # 계정 확인

# 현재 상태 확인
gcloud run services describe backend --region asia-northeast3 \
  --format='value(status.url,status.latestReadyRevisionName)'
gcloud sql instances list
gh auth login                         # PR 작업용 (GitHub 계정)
```

이후 배포·모델 교체·롤백·시연일 설정은 전부 [DEPLOY_RUNBOOK.md](DEPLOY_RUNBOOK.md) 명령
그대로다. 빌드(`gcloud builds submit`)는 로컬 디렉터리를 업로드하므로 **2장의 자산이 배치된
노트북에서만** 가능하다. 다른 팀원이 배포하려면 같은 zip과 GCP 프로젝트 권한(IAM 편집자
이상)이 필요하다.

## 5. 현재 배포 상태 (2026-08-24 기준)

| 항목 | 값 |
|---|---|
| 서비스 URL | `https://backend-827716553089.asia-northeast3.run.app` |
| 리비전 | `backend-00010-fgx` — 최종 모델 + 공휴일 폴백 + 실시간 배차간격 |
| 리소스 | 메모리 **4Gi**, cpu-boost, **min=max=1 (시연용 상시 가동)** |
| 시연 후 복귀 | `gcloud run services update backend --region asia-northeast3 --min-instances 0 --max-instances 2` |
| 환경변수 | `HEADWAY_SCHEDULE_ENABLED=false` (계획 배차간격 공급자 비활성, 실시간 계산과 무관) |

검증 이력과 특이사항은 [DEPLOYMENT_SMOKE_TEST.md](DEPLOYMENT_SMOKE_TEST.md).

## 6. Claude Code로 이어서 작업할 때

Claude Code의 프로젝트 메모리는 **저장소 경로 기준**으로 저장된다
(`~/.claude/projects/<경로를-하이픈으로-바꾼-이름>/memory/`). 새 노트북에서는 비어 있으므로
zip의 `claude-memory/` 4개 파일을 새 노트북의 해당 디렉터리에 복사한다.

```bash
# 예: 저장소를 /Users/<이름>/Desktop/backend 에 clone했다면
mkdir -p ~/.claude/projects/-Users-<이름>-Desktop-backend/memory
cp claude-memory/* ~/.claude/projects/-Users-<이름>-Desktop-backend/memory/
```

디렉터리 이름은 Claude Code를 그 저장소에서 한 번 실행하면 자동 생성되니, 먼저 한 번 열어
정확한 경로를 확인한 뒤 복사해도 된다. 메모리 내용: 커밋은 사용자가 직접(제안만), 다른 팀
레포 액션 전 확인, GCP 배포 상태. `HANDOFF.md`도 같이 두면 맥락이 더 잘 이어진다.

## 7. 주의

- **`.env`에는 공공데이터포털 인증키가 들어 있다.** zip을 공용 채팅·공개 드라이브에 올리지
  말고, 직접 전달(AirDrop·USB·개인 드라이브)한다. 유출 의심 시 공공데이터포털에서 키 재발급 후
  Secret Manager `topis-api-key`도 새 버전으로 갱신한다.
- DB 비밀번호는 로컬에 없다. Secret Manager `db-password`에만 있으며 Cloud Run이 직접 읽는다.
- 모델을 교체받으면 [DEPLOY_RUNBOOK.md](DEPLOY_RUNBOOK.md) 2장 절차(테스트 → 메모리 재산정 →
  빌드·배포)를 따른다.
