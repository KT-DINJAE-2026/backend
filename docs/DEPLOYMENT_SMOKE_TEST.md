# 시연 환경 배포 검증 기록

GCP 시연 환경 배포 후 Postman·curl로 전 엔드포인트를 검증한 결과를 남긴다.
배포 구성은 [CLOUD_ARCHITECTURE.md](CLOUD_ARCHITECTURE.md), 재배포 절차는
[DEPLOY_RUNBOOK.md](DEPLOY_RUNBOOK.md) 참고. 이미지를 다시 배포하거나 모델·기반정보를
교체하면 이 문서의 시나리오를 다시 실행해 결과를 갱신한다.

## 배포 정보

| 항목 | 값 |
|---|---|
| 서비스 URL | `https://backend-827716553089.asia-northeast3.run.app` |
| 리비전 | `backend-00007-nvz` (2026-08-24 — 최종 모델 + 공휴일 API 폴백, **메모리 4Gi + cpu-boost**) |
| GCP 프로젝트 / 리전 | `kt-dinjae` / `asia-northeast3` |
| 이미지 | `asia-northeast3-docker.pkg.dev/kt-dinjae/backend/backend:latest` |
| DB | Cloud SQL `backend-mysql` (MySQL 8.0, db-f1-micro) |
| 적재 기반정보 | 기준일 2025-04-01 — 정류장 13,307 / 노선 832 / 경유 38,709건 |
| 모델 | **PMML A/B 배포용 최종본** (2026-08-24 전달, 1~12월 전체 학습, golden test 20건 통과) |

## API 문서·테스트 도구

- Swagger UI: <https://backend-827716553089.asia-northeast3.run.app/swagger-ui.html>
- OpenAPI JSON: <https://backend-827716553089.asia-northeast3.run.app/v3/api-docs>
- Postman: **Import → 위 OpenAPI JSON URL 붙여넣기** → "교통약자 입석 위험 안내 API" 컬렉션이
  생성된다. 요청 URL이 `{{baseUrl}}` 변수면 컬렉션 Variables에서 서비스 URL로 설정한다.

## 검증 결과 (2026-08-24, 최종 모델 리비전, 전 항목 통과)

| # | 요청 | 기대 | 결과 |
|---|---|---|---|
| 1 | `GET /health` | 200, `status: UP` | ✅ |
| 2 | `GET /api/v1/stops/107000087/context` | 성북구청.성북경찰서 + 목적지 후보와 경유 노선 | ✅ |
| 3 | `GET /api/v1/stops/search?originStopId=107000087&query=성북` | 이름 일치 정류장 목록 | ✅ |
| 4 | `POST /api/v1/journeys/predictions` — `107000087→107000089` | `SUCCESS` + 실시간 응답: 실제 차량 `tripId`, 노선 5개, **최종 모델** 입석 예측 | ✅ |
| 5 | 같은 요청 — 목적지 `100000147` | `SUCCESS` + 실시간 예측 (구 고정 `INSUFFICIENT_DATA` 시나리오 소멸 — 아래 참고) | ✅ |
| 6 | 같은 요청 — 존재하지 않는 정류장 `999999999` | 404, `STOP_NOT_FOUND` | ✅ |
| 7 | 같은 요청 — `originStopId: "abc"` (형식 위반) | 400, `INVALID_REQUEST` | ✅ |
| 8 | `GET /swagger-ui.html` | 200 | ✅ |

요청 본문 예시(#4):

```json
{"originStopId": "107000087", "destinationStopId": "107000089"}
```

## 실연동 확인 사실

**2026-08-24 (최종 모델 리비전):**

- **KASI 공휴일 API(apis.data.go.kr)는 Cloud Run에서 간헐 차단된다** — 연결 타임아웃 실측.
  이 때문에 예측 API 전체가 502로 죽는 문제를 내장 공휴일 목록 폴백(PR #14)으로 해결했고,
  배포 직후 실차단 상황에서 폴백 발동("공휴일 API 실패로 내장 목록을 사용합니다")과
  `SUCCESS` 응답을 함께 확인했다. TOPIS(`ws.bus.go.kr`)는 차단이 관찰되지 않았다.
- 최종 모델(259MB)은 **2GiB 배포가 적재 파싱 피크 OOM으로 실패**해 4GiB + cpu-boost로
  운영한다. 콜드 스타트는 약 47초(모델 파싱 약 40초 포함) — 시연일 `--min-instances 1` 필수.
- 검증 시각(일요일 14:30 KST)에는 전 차량 입석 부담 `LOW`. 혼잡 시간대 관찰은 여전히 남은 항목.

**2026-08-19 15:50 KST (실연동 최초 리비전):**

- **Cloud Run(구글 IP)에서 TOPIS 호출이 정상 동작한다.** 배포 전 우려였던 공공 API의
  해외·데이터센터 IP 차단은 발생하지 않았다. `getArrInfoByRouteAll` 실호출로 차량
  ID·도착초·저상 여부가 반환됐다.
- `tripId`가 실제 차량 ID(예: `107012033`)로 바뀌었다. `mock-trip-*`이 나오면 demo
  프로필이거나 구 리비전이다.
- 검증 시각(수요일 15:50, 비혼잡 시간대)에는 전 차량 입석 부담 `LOW`/0분이었다.
  혼잡 시간대(출퇴근)에 `MEDIUM`/`HIGH`가 나오는지는 추가 관찰이 필요하다.

## 특이사항·주의

- **FE 데모 시나리오 변경**: `107000087→100000147`은 더 이상 고정 `INSUFFICIENT_DATA`가
  아니다(해당 구간이 모델 도메인 안이라 실예측 `SUCCESS` 반환). 고정 시나리오가 필요하면
  `demo` 프로필을 사용해야 한다.
- **`reasonCode`가 2종 추가**됐다: `NO_REALTIME_ARRIVAL_DATA`(직통 노선은 있으나 실시간
  도착정보 전무 — 심야 등, 이때 `routes`가 빈 배열일 수 있음), `MODEL_UNAVAILABLE`(모델
  미적재). 기존 `NOT_ENOUGH_HISTORICAL_SAMPLES`는 학습 범위 밖 입력에 반환된다.
- 심야·미운행 시간대에는 `INSUFFICIENT_DATA` + `NO_REALTIME_ARRIVAL_DATA`가 정상 응답이다.
  시연 리허설은 버스 운행 시간대에 해야 한다.
- 검색 결과의 `servedRoutes: []`는 정상이다. 직통 노선이 없는 정류장도 반환하되 빈 배열로
  표시해 FE가 "검색 실패"와 "직통 없음"을 구분한다(`StopService` 참고).
- 유휴 상태(min-instances 0)에서 첫 요청은 콜드 스타트로 10초 이상 걸릴 수 있다. 시연 당일
  체크리스트([CLOUD_ARCHITECTURE.md](CLOUD_ARCHITECTURE.md) 4장)대로 `--min-instances 1`을 설정한다.
- 오류 응답의 `traceId`는 Cloud Logging에서 해당 요청 로그를 찾는 키다
  (`gcloud logging read 'textPayload:<traceId>'`).

## 이전 검증 이력

- 2026-08-18 최초 배포(테스트 데이터 리비전): 전 항목 통과. 당시 4번은 `mock-trip-*` 고정
  응답, 5번은 고정 `INSUFFICIENT_DATA`(`NOT_ENOUGH_HISTORICAL_SAMPLES`)였다.
