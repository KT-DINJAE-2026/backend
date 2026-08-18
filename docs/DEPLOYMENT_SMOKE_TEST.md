# 시연 환경 배포 검증 기록

2026-08-18 GCP 시연 환경 최초 배포 후 Postman·curl로 전 엔드포인트를 검증한 결과를 남긴다.
배포 구성은 [CLOUD_ARCHITECTURE.md](CLOUD_ARCHITECTURE.md) 참고. 이미지를 다시 배포하거나
모델·기반정보를 교체하면 이 문서의 시나리오를 다시 실행해 결과를 갱신한다.

## 배포 정보

| 항목 | 값 |
|---|---|
| 서비스 URL | `https://backend-827716553089.asia-northeast3.run.app` |
| GCP 프로젝트 / 리전 | `kt-dinjae` / `asia-northeast3` |
| 이미지 | `asia-northeast3-docker.pkg.dev/kt-dinjae/backend/backend:latest` |
| DB | Cloud SQL `backend-mysql` (MySQL 8.0, db-f1-micro) |
| 적재 기반정보 | 기준일 2025-04-01 — 정류장 13,307 / 노선 832 / 경유 38,709건 |
| 모델 | PMML A/B 임시 버전 (2026-08-14 전달본, 이미지 내장) |

## API 문서·테스트 도구

- Swagger UI: <https://backend-827716553089.asia-northeast3.run.app/swagger-ui.html>
- OpenAPI JSON: <https://backend-827716553089.asia-northeast3.run.app/v3/api-docs>
- Postman: **Import → 위 OpenAPI JSON URL 붙여넣기** → "교통약자 입석 위험 안내 API" 컬렉션이
  생성된다. 요청 URL이 `{{baseUrl}}` 변수면 컬렉션 Variables에서 서비스 URL로 설정한다.

## 검증 결과 (2026-08-18, 전 항목 통과)

| # | 요청 | 기대 | 결과 |
|---|---|---|---|
| 1 | `GET /health` | 200, `status: UP` | ✅ |
| 2 | `GET /api/v1/stops/107000087/context` | 성북구청.성북경찰서 + 목적지 후보(보문역·신설동로터리)와 경유 노선 | ✅ |
| 3 | `GET /api/v1/stops/search?originStopId=107000087&query=성북` | 이름 일치 정류장 목록 | ✅ (아래 특이사항 참고) |
| 4 | `POST /api/v1/journeys/predictions` — `107000087→107000089` | `SUCCESS`, 노선 5개, 입석 부담 LOW~HIGH 혼합 | ✅ |
| 5 | 같은 요청 — 목적지 `100000147` | `INSUFFICIENT_DATA`, `NOT_ENOUGH_HISTORICAL_SAMPLES`, confidence `UNAVAILABLE` | ✅ |
| 6 | 같은 요청 — 존재하지 않는 정류장 `999999999` | 404, `STOP_NOT_FOUND` | ✅ |
| 7 | 같은 요청 — `originStopId: "abc"` (형식 위반) | 400, `INVALID_REQUEST` | ✅ |
| 8 | `GET /swagger-ui.html` | 200 | ✅ |

요청 본문 예시(#4):

```json
{"originStopId": "107000087", "destinationStopId": "107000089"}
```

## 특이사항·주의

- **응답의 도착·혼잡·입석 정보는 테스트 데이터다.** 여정 API는 팀 합의대로
  `JourneyTestDataService`의 데모 응답을 반환하며(`tripId`가 `mock-trip-*`), `arrivalMinutes`도
  여기서 생성된다. **TOPIS 실시간 도착정보는 현재 어떤 응답 경로에서도 호출되지 않으므로**,
  실제 예측·도착정보 연동 시 서울 리전에서의 TOPIS 호출 가능 여부를 별도로 검증해야 한다.
- 검색 결과의 `servedRoutes: []`는 정상이다. 직통 노선이 없는 정류장도 반환하되 빈 배열로
  표시해 FE가 "검색 실패"와 "직통 없음"을 구분한다(`StopService` 참고).
- 유휴 상태(min-instances 0)에서 첫 요청은 콜드 스타트로 10초 이상 걸릴 수 있다. 시연 당일
  체크리스트([CLOUD_ARCHITECTURE.md](CLOUD_ARCHITECTURE.md) 4장)대로 `--min-instances 1`을 설정한다.
- 오류 응답의 `traceId`는 Cloud Logging에서 해당 요청 로그를 찾는 키다
  (`gcloud logging read 'textPayload:<traceId>'`).
