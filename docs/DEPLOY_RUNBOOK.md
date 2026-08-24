# 배포 런북 — 코드 수정 후 재배포 절차

일상 운영에서 "무엇이 바뀌었을 때 무엇을 실행하는지"를 정리한다. 최초 리소스 셋업은
[CLOUD_ARCHITECTURE.md](CLOUD_ARCHITECTURE.md), 배포 후 검증 시나리오는
[DEPLOYMENT_SMOKE_TEST.md](DEPLOYMENT_SMOKE_TEST.md) 참고. 아래 명령은 전부 실제 값
(`kt-dinjae` 프로젝트) 기준이다.

## 전제

- `gcloud auth login` 완료, `gcloud config set project kt-dinjae` 상태
- **로컬에 `models/`(PMML 2개)와 `masterdata/`(기준일 DAT 3개)가 채워져 있어야 한다.**
  모델·기반정보가 git에 없으므로 GitHub Actions로는 이미지를 만들 수 없고, main 머지 후에도
  배포는 반드시 로컬에서 제출한다.

## 1. 코드만 수정했을 때 (가장 흔한 경우)

main에 머지(또는 배포할 브랜치 체크아웃) 후 두 명령이면 끝난다.

```bash
# 1) 이미지 빌드·푸시 (로컬 디렉터리 업로드 → Cloud Build)
gcloud builds submit --tag asia-northeast3-docker.pkg.dev/kt-dinjae/backend/backend:latest

# 2) 새 리비전 배포 — 이미지만 갈아끼운다
gcloud run deploy backend \
  --image asia-northeast3-docker.pkg.dev/kt-dinjae/backend/backend:latest \
  --region asia-northeast3
```

환경변수·시크릿·Cloud SQL 연결·서비스 계정 등 **기존 설정은 새 리비전에 그대로 유지**되므로
다시 지정할 필요 없다. 배포는 새 리비전이 준비된 뒤 트래픽이 넘어가는 방식이라 다운타임이 없다.

> **주의 — `--set-env-vars`를 다시 주지 말 것.** 이 플래그는 지정한 목록으로 **전체를
> 대체**하기 때문에 빠뜨린 변수가 날아간다. 변수 하나를 추가·변경할 때는
> `--update-env-vars KEY=VALUE`(병합)를 쓴다. 시크릿도 같은 원리다
> (`--set-secrets` 대신 `--update-secrets`).

배포 후 확인:

```bash
curl https://backend-827716553089.asia-northeast3.run.app/health
```

응답 스펙이 바뀌었으면 [DEPLOYMENT_SMOKE_TEST.md](DEPLOYMENT_SMOKE_TEST.md)의 시나리오를
Postman으로 다시 돌리고 결과 표를 갱신한다.

## 2. AI 모델을 교체할 때

1. `models/`의 `model_a.pmml`·`model_b.pmml`을 새 파일로 교체하고, AI팀이 golden test
   샘플을 함께 주면 `models/golden_test_samples.json`도 갱신
2. **배포 전에 로컬 전체 테스트**로 입력·출력 계약 유지 확인 — 모델 파일이 있으면 실모델
   스모크 테스트가, golden 샘플이 있으면 Python 동등성 대조(`GoldenModelRegressionTests`)가
   자동 활성화된다 ([models/README.md](../models/README.md)의 "모델 교체 시 재확인할 것" 참고).
   모델 크기가 바뀌면 스모크 테스트 로그의 적재 시간·상주 힙으로 Cloud Run 메모리를 재산정한다
   (2026-08-24 최종본: 767MiB → `--memory 2Gi` 필요)

   ```bash
   ./gradlew test
   ```

3. 1번과 동일하게 빌드·배포 (모델은 이미지에 내장되므로 이미지 재빌드가 곧 모델 교체다)

## 3. 기반정보(DAT)를 교체할 때

1. `masterdata/archive/`에서 새 기준일의 STTN·ROUTE·ROUTESTTN 세 파일을 `masterdata/` 루트로
   복사 (같은 기준일 세트만, [masterdata/README.md](../masterdata/README.md) 참고)
2. 1번과 동일하게 빌드·배포
3. **적재 잡을 다시 실행** — upsert 방식이라 중복 실행해도 안전하다. 잡 설정의 파일 경로가
   새 파일명과 다르면 먼저 갱신한다.

   ```bash
   # 파일명(기준일)이 바뀐 경우에만:
   gcloud run jobs update backend-init --region asia-northeast3 \
     --update-env-vars MASTER_STOP_FILE=/masterdata/STTN_<날짜>.dat,MASTER_ROUTE_FILE=/masterdata/ROUTE_<날짜>.dat,MASTER_ROUTE_STOP_FILE=/masterdata/ROUTESTTN_<날짜>.dat

   # 새 이미지로 적재 실행 (db-f1-micro 기준 약 15분 소요)
   gcloud run jobs update backend-init --region asia-northeast3 \
     --image asia-northeast3-docker.pkg.dev/kt-dinjae/backend/backend:latest
   gcloud run jobs execute backend-init --region asia-northeast3 --wait
   ```

## 4. 문제가 생겼을 때 — 롤백

Cloud Run은 이전 리비전을 이미지 다이제스트째 보관하므로 `:latest`를 덮어썼어도 되돌릴 수 있다.

```bash
# 리비전 목록 확인 (최신이 위)
gcloud run revisions list --service backend --region asia-northeast3

# 직전 리비전으로 트래픽 100% 전환
gcloud run services update-traffic backend --region asia-northeast3 \
  --to-revisions <리비전이름>=100
```

원인 조사는 Cloud Logging에서 한다. 오류 응답의 `traceId`가 검색 키다.

```bash
gcloud logging read 'resource.type=cloud_run_revision AND resource.labels.service_name=backend AND severity>=ERROR' --limit=20 --freshness=1h
```

## 5. 시연 당일

[CLOUD_ARCHITECTURE.md](CLOUD_ARCHITECTURE.md) 4장 체크리스트를 따른다. 핵심 두 가지:

```bash
# 시연 전: 콜드 스타트 방지
gcloud run services update backend --region asia-northeast3 --min-instances 1

# 시연 후: 비용 절감 복귀
gcloud run services update backend --region asia-northeast3 --min-instances 0
```

장기간 안 쓸 때는 Cloud SQL을 중지하면 과금 대부분이 멈춘다
(`gcloud sql instances patch backend-mysql --activation-policy=NEVER`,
재시작은 `--activation-policy=ALWAYS`). **DB가 중지된 동안 서비스는 기동 실패한다.**
