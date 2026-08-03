# 프론트엔드 연동 가이드

백엔드 API 사용법과 `API_CONTRACT_DRAFT.md` v0.6 대비 변경 사항을 정리한 문서입니다.

- 기준일: 2026-08-03
- 기본 경로: `/api/v1`
- 데이터 형식: `application/json; charset=UTF-8`
- 인증: 없음

아래 응답은 실제 demo 서버 응답을 기준으로 작성했으며, 설명에 불필요한 배열 항목은 일부 생략했습니다. 생략된 예시는 해당 위치에 별도로 표시합니다.

---

## 1. 먼저 확인할 것 — FE 필수 수정 사항

현재 FE의 server 모드를 연결하려면 아래 세 가지를 수정해야 합니다.

### 1.1 API 오류 body 파싱

`src/api/busApi.js`의 `request()`가 오류 응답 body를 읽지 않고 있습니다.

```js
if (!response.ok) {
  throw new Error(`API 요청 실패: ${response.status}`);   // code가 사라짐
}
```

백엔드는 계약서 12장대로 **직통 노선이 없을 때 `404 NO_DIRECT_ROUTE`** 를 반환합니다. 지금 코드로는 `code`를 읽을 수 없어 "직통 버스 없음" 화면 대신 "서버 오류" 화면이 뜹니다. Figma의 `04-C-no-direct-route` 화면에 도달하려면 이 수정이 필요합니다.

```js
if (!response.ok) {
  const error = await response.json().catch(() => ({}));
  const apiError = new Error(error.message ?? `API 요청 실패: ${response.status}`);
  apiError.status = response.status;
  apiError.code = error.code;          // NO_DIRECT_ROUTE / STOP_NOT_FOUND / ...
  apiError.traceId = error.traceId;
  throw apiError;
}
```

`STOP_NOT_FOUND`, `STOP_DIRECTION_MISMATCH` 구분에도 같은 수정이 필요합니다.

오류 body를 읽는 것만으로 화면이 바뀌지는 않습니다. 여정 분석의 `catch`에서도 `error.code`를 확인해 Figma의 상태 화면으로 분기해야 합니다.

```js
try {
  // 여정 분석 요청
} catch (error) {
  if (error.code === "NO_DIRECT_ROUTE") {
    setScreen("no-direct-route");
    return;
  }
  setScreen("error");
}
```

`no-direct-route` 상태에 대응하는 화면 컴포넌트도 함께 연결해야 합니다.

### 1.2 도착 정류장 검색 API 연결

현재 FE는 `context` 응답의 초기 추천 목록만 브라우저에서 필터링하며 `/api/v1/stops/search`를 호출하지 않습니다. `busApi.js`에 검색 함수를 추가하고 검색어 입력 시 서버 검색 결과를 사용해야 합니다.

```js
async function searchDestinationStops({ originStopId, query }) {
  const params = new URLSearchParams({ originStopId, query });
  return request(`/api/v1/stops/search?${params.toString()}`);
}

export const busApi = {
  getBootstrap,
  searchDestinationStops,
  getJourneyPrediction,
};
```

검색 입력마다 호출한다면 debounce를 적용하고, 앞선 요청보다 늦게 도착한 응답이 최신 검색 결과를 덮어쓰지 않도록 요청 순서를 관리해 주세요.

### 1.3 제거된 Mock 전용 필드 처리

서버 응답에는 `searchKeywords`가 없습니다. 기존 로컬 필터 코드를 임시로 유지한다면 아래처럼 기본 빈 배열을 사용해야 합니다.

```js
...(destinationStop.searchKeywords ?? [])
```

`summaryMessage`, `segments[].description`, `predictionBasis.description` 등 표시 문구도 4.1절의 enum 매핑에 따라 FE에서 생성해야 합니다.

---

## 2. 서버 실행과 연결

### 백엔드 실행 (FE 연동 검증용 `demo` 프로필)

AI 예측과 실시간 도착정보 연동이 끝나기 전에도 FE가 실제 HTTP 연결을 검증할 수 있도록 격리된 프로필을 제공합니다. 메모리 H2에 고정 데이터를 넣고 응답하므로 DB나 외부 API 키가 필요 없습니다.

```powershell
.\gradlew.bat bootRun --args='--spring.profiles.active=demo'
```

### FE 환경변수

```env
VITE_API_MODE=server
VITE_API_BASE_URL=http://localhost:8080
```

### Swagger

`http://localhost:8080/swagger-ui.html` 에서 세 엔드포인트와 성공·데이터부족 예시를 확인할 수 있습니다.

### CORS

`/api/**` 경로에 아래 Origin의 `GET`, `POST`, `OPTIONS`를 허용합니다. 허용 헤더는 `Accept`, `Content-Type`입니다.

- `http://localhost:5173`
- `https://kd-dinjae-2026-fe.vercel.app`

정식 Vercel 주소는 이미 등록되어 있습니다. Vercel Preview URL이나 추가 배포 도메인을 사용할 경우 해당 Origin을 별도로 공유해 주세요. 현재 Preview URL은 CORS 허용 대상이 아닙니다.

---

## 3. API

| Method | URL | 설명 |
|---|---|---|
| `GET` | `/api/v1/stops/{stopId}/context` | QR 출발 정류장 + 초기 도착 정류장 목록 |
| `GET` | `/api/v1/stops/search?originStopId=&query=` | 도착 정류장 검색 |
| `POST` | `/api/v1/journeys/predictions` | 직통 버스 여정 분석 |

### 3.1 QR 진입 — `GET /api/v1/stops/{stopId}/context`

`stopId`는 **숫자 9자리**여야 합니다. 형식이 다르면 `400 INVALID_REQUEST`입니다.

```http
GET /api/v1/stops/107000087/context
```

```json
{
  "generatedAt": "2026-08-03T20:44:21.7900684+09:00",
  "currentStop": {
    "stopId": "107000087",
    "arsId": "08177",
    "stopName": "성북구청.성북경찰서",
    "directionDescription": "동묘앞 방면",
    "location": { "latitude": 37.58815138, "longitude": 127.01743066 }
  },
  "destinationStops": [
    {
      "stopId": "107000089",
      "arsId": "08179",
      "stopName": "보문역2번출구",
      "directionDescription": "동묘앞 방면",
      "servedRoutes": [
        { "routeId": "100100129", "routeNumber": "1014" },
        { "routeId": "100100008", "routeNumber": "103" },
        { "routeId": "100100021", "routeNumber": "142" },
        { "routeId": "100100031", "routeNumber": "152" }
      ],
      "location": { "latitude": 37.58585142, "longitude": 127.01892094 }
    },
    {
      "stopId": "100000147",
      "arsId": "01243",
      "stopName": "신설동역오거리",
      "directionDescription": "동묘앞 방면",
      "servedRoutes": [
        { "routeId": "100100129", "routeNumber": "1014" },
        { "routeId": "100100008", "routeNumber": "103" },
        { "routeId": "100100031", "routeNumber": "152" }
      ],
      "location": { "latitude": 37.57569473, "longitude": 127.02284143 }
    }
  ]
}
```

`destinationStops`는 전체 목록이 아니라 초기 추천 목록입니다. 서버 설정(`app.api.initial-destination-stop-ids`)으로 바꿀 수 있으니 원하는 구성이 있으면 알려주세요.

### 3.2 도착 정류장 검색 — `GET /api/v1/stops/search`

| Query | 필수 | 제약 |
|---|---|---|
| `originStopId` | O | 숫자 9자리 |
| `query` | O | 1~50자. 정류장명·ARS 번호·노선 번호 |

```http
GET /api/v1/stops/search?originStopId=107000087&query=%EB%B3%B4%EB%AC%B8%EC%97%AD
```

```json
{
  "destinationStops": [
    {
      "stopId": "107000089",
      "arsId": "08179",
      "stopName": "보문역2번출구",
      "directionDescription": "동묘앞 방면",
      "servedRoutes": [
        { "routeId": "100100129", "routeNumber": "1014" },
        { "routeId": "100100008", "routeNumber": "103" },
        { "routeId": "100100021", "routeNumber": "142" },
        { "routeId": "100100031", "routeNumber": "152" }
      ],
      "location": { "latitude": 37.58585142, "longitude": 127.01892094 }
    }
  ]
}
```

검색 결과가 없으면 `200`과 빈 배열입니다.

```json
{ "destinationStops": [] }
```

한글 검색어는 **UTF-8 퍼센트 인코딩**으로 보내야 합니다. `encodeURIComponent()`를 쓰면 됩니다.

계약서 6장대로, 직통 노선이 없는 정류장도 검색 결과에서 숨기지 않고 `servedRoutes: []`로 반환합니다. 그 정류장으로 여정 분석을 요청하면 `404 NO_DIRECT_ROUTE`가 옵니다.

### 3.3 여정 분석 — `POST /api/v1/journeys/predictions`

```json
{ "originStopId": "107000087", "destinationStopId": "107000089" }
```

두 ID 모두 숫자 9자리 필수입니다.

#### 예측 성공

실제 demo 응답에는 1014·103·142·152번 네 노선이 포함됩니다. 아래 예시는 응답 구조 확인을 위해 1014·152번 두 항목만 발췌했습니다.

```json
{
  "status": "SUCCESS",
  "generatedAt": "2026-08-03T20:44:54.1033165+09:00",
  "originStopId": "107000087",
  "destinationStopId": "107000089",
  "predictionBasis": { "confidence": "MEDIUM" },
  "routes": [
    {
      "tripId": "mock-trip-100100129-1405",
      "routeId": "100100129",
      "routeNumber": "1014",
      "direction": "동묘앞 방면",
      "vehicleType": "저상버스",
      "isLowFloor": true,
      "arrivalMinutes": 5,
      "travelMinutes": 3,
      "standingBurdenMinutes": 0,
      "standingBurdenLevel": "LOW",
      "segments": [
        {
          "fromStopId": "107000087",
          "fromStopName": "성북구청.성북경찰서",
          "toStopId": "107000089",
          "toStopName": "보문역2번출구",
          "durationMinutes": 3,
          "congestionLevel": "RELAXED"
        }
      ]
    },
    {
      "tripId": "mock-trip-100100031-1402",
      "routeId": "100100031",
      "routeNumber": "152",
      "direction": "동대문 방면",
      "vehicleType": "저상버스",
      "isLowFloor": true,
      "arrivalMinutes": 2,
      "travelMinutes": 3,
      "standingBurdenMinutes": 3,
      "standingBurdenLevel": "HIGH",
      "segments": [
        {
          "fromStopId": "107000087",
          "fromStopName": "성북구청.성북경찰서",
          "toStopId": "107000089",
          "toStopName": "보문역2번출구",
          "durationMinutes": 3,
          "congestionLevel": "VERY_CROWDED"
        }
      ]
    }
  ]
}
```

계약서 10장의 검증 규칙은 서버가 지킵니다. 구간 순서·연속성, 구간 시간 합계 = `travelMinutes`, `RELAXED` 제외 구간 합계 = `standingBurdenMinutes`, 한 응답 내 `tripId` 유일성이 보장됩니다.

#### 혼잡도 데이터 부족

실제 demo 응답에는 1014·103·152번 세 노선이 포함됩니다. 아래 예시는 1014번 한 항목만 발췌했습니다.

```json
{
  "status": "INSUFFICIENT_DATA",
  "reasonCode": "NOT_ENOUGH_HISTORICAL_SAMPLES",
  "generatedAt": "2026-08-03T20:44:54.140828+09:00",
  "originStopId": "107000087",
  "destinationStopId": "100000147",
  "predictionBasis": { "confidence": "UNAVAILABLE" },
  "routes": [
    {
      "tripId": "mock-trip-100100129-1404",
      "routeId": "100100129",
      "routeNumber": "1014",
      "direction": "동묘앞 방면",
      "vehicleType": "저상버스",
      "isLowFloor": true,
      "arrivalMinutes": 4,
      "travelMinutes": 10
    }
  ]
}
```

`standingBurdenMinutes`, `standingBurdenLevel`, `segments`는 `null`이 아니라 **필드 자체가 빠집니다.** 계약서 3장의 "선택 값이 없을 때는 `null`보다 필드 생략" 규칙 그대로입니다.

#### 오류

```json
{ "code": "NO_DIRECT_ROUTE", "message": "두 정류장을 잇는 직통 노선이 없습니다.", "traceId": "166f068f" }
```

| HTTP | `code` | 상황 |
|---|---|---|
| `400` | `INVALID_REQUEST` | 필수값 누락, ID 형식 오류(9자리 아님) |
| `404` | `STOP_NOT_FOUND` | 출발 또는 도착 정류장 없음 |
| `404` | `NO_DIRECT_ROUTE` | 두 정류장을 잇는 직통 버스 없음 |
| `409` | `STOP_DIRECTION_MISMATCH` | 노선은 지나지만 그 방향으로는 이동 불가 |
| `500` | `INTERNAL_SERVER_ERROR` | 서버 처리 실패 |

`message`는 사용자 안내에 쓰지 말고 로그 확인용으로만 쓰세요. `traceId`로 서버 로그와 대조할 수 있습니다.

---

## 4. 계약서 v0.6 대비 변경 사항

### 4.1 응답에서 빠진 필드 — FE가 생성해야 합니다

역할 협의에 따라 **백엔드는 enum과 수치만 주고 표시 문구는 FE가 결정**하기로 정했습니다. 문구를 고칠 때마다 백엔드를 재배포하지 않아도 되는 이점도 있습니다.

| 빠진 필드 | 대체 방법 |
|---|---|
| `displayName` | `stopName`을 그대로 쓰거나 FE에서 가공 |
| `landmark` | 동명 정류장 구분은 `arsId` + `directionDescription` + `location`으로 |
| `searchKeywords` | 서버 검색을 쓰므로 불필요 |
| `roadviewFallback` | FE 자체 자산으로 관리 |
| `summaryMessage` | `standingBurdenLevel` enum으로 생성 |
| `segments[].description` | `congestionLevel` enum으로 생성 |
| `predictionBasis.description` | `confidence` enum으로 생성 |
| `fromStopDisplayName` / `toStopDisplayName` | `fromStopName` / `toStopName` 사용 |
| `dataSource` | 운영 응답에서는 제공하지 않음 |

`predictionBasis`는 `confidence` 하나만 있는 객체입니다.

```json
"predictionBasis": { "confidence": "MEDIUM" }
```

### 4.2 `routeNumber`에 "번"이 붙지 않습니다

계약서에 표기가 섞여 있었습니다(3장 표는 `1014번`, `bootstrap.json`은 `1014`). **서버는 모든 응답에서 "번" 없이 `"1014"`로 통일**합니다. 화면 표시용 "번"은 FE에서 붙여주세요.

### 4.3 `NO_DIRECT_ROUTE`는 `404`입니다

계약서 6장(200 + `servedRoutes: []`)과 12장(404)이 충돌했는데, **12장 기준으로 확정**했습니다. 두 규정은 대상이 달라 실제로는 충돌하지 않습니다.

- **검색 API**: 직통이 없어도 정류장을 숨기지 않고 `200` + `servedRoutes: []`
- **여정 분석 API**: 그 정류장으로 요청하면 `404 NO_DIRECT_ROUTE`

1장의 `busApi.js` 수정이 필요한 이유입니다.

### 4.4 좌표 정밀도가 소수점 8자리입니다

계약서 예시는 10자리(`37.5858514183`)였으나 서버는 8자리(`37.58585142`)로 반환합니다. 약 1mm 차이라 카카오 로드뷰 조회에는 영향이 없습니다.

### 4.5 `generatedAt`에 나노초가 포함됩니다

`2026-08-03T20:44:54.1033165+09:00` 형식입니다. ISO 8601이라 `new Date()`로 그대로 파싱됩니다.

### 4.6 입력 검증이 추가됐습니다

`stopId`·`originStopId`·`destinationStopId`는 숫자 9자리, `query`는 1~50자입니다. 위반 시 `400 INVALID_REQUEST`입니다.

### 4.7 `currentStop`에도 `directionDescription`이 있습니다

계약서와 동일하지만, 값은 노선 종점명 기반으로 `"동묘앞 방면"` 형태로 생성됩니다.

---

## 5. 아직 실제 값이 아닌 것

현재 여정 분석 API는 **FE 계약 검증용 테스트 데이터**를 반환합니다. 응답 구조는 확정본이지만 값은 실제가 아닙니다.

| 필드 | 현재 상태 |
|---|---|
| `arrivalMinutes`, `tripId`, `vehicleType`, `isLowFloor` | 고정 테스트 값. TOPIS 클라이언트 기반은 구현됐으나 공공데이터포털 인증키 등록 오류로 실호출 검증 대기 중 |
| `travelMinutes`, `standingBurdenLevel`, `congestionLevel`, `confidence` | 고정 테스트 값. AI 입력·출력 및 연동 방식은 회의 후 확정 예정 |

현재 테스트 응답 구조를 기준으로 FE 연동을 진행할 수 있습니다. AI 연동 규격 확정 과정에서 변경이 필요하면 API 버전과 변경 사항을 별도로 공유합니다.

### AI 데이터 범위 확정 후 데모 정류장이 바뀔 수 있습니다

현재 확보한 AI 학습 데이터 범위는 **서초구 2025년 4월**분이므로 성북구(성북구청·보문역)는 실제 예측 대상이 아닙니다. 다만 AI 연동 방식과 최종 데모 구간은 아직 확정되지 않았습니다. 아래 정류장은 변경 후보이며, 확정 전에는 Mock JSON이나 QR `stopId`를 바꾸지 마세요.

| 용도 | 정류장 | `stopId` | `arsId` | 좌표 |
|---|---|---|---|---|
| 출발 (QR) | 고속터미널 | `121000019` | `22019` | 37.506300, 127.005140 |
| 도착 — 예측 성공 | 신반포역.세화여중고 | `121000021` | `22021` | 37.503420, 126.995720 |
| 도착 — 데이터 부족 | 매헌시민의숲 | `121001344` | `22046` | 37.470360, 127.038750 |

현재는 성북구 정류장(`107000087` → `107000089` / `100000147` / `121009999`)으로 세 시나리오를 모두 확인할 수 있습니다. 변경이 확정되면 새 ID와 적용 일정을 별도로 공유합니다.

---

## 6. 요청 사항 정리

1. 🔴 `busApi.js`에서 오류 body를 파싱하고 `error.code` 보존 (1.1)
2. 🔴 `NO_DIRECT_ROUTE` 등 오류 코드별 화면 분기 구현 (1.1)
3. 🔴 `/api/v1/stops/search` 호출 함수와 검색 결과 상태 연결 (1.2)
4. `searchKeywords` 제거 또는 기본 빈 배열 처리 (1.3)
5. 빠진 문구 필드들을 FE에서 enum 기반으로 생성 (4.1)
6. `routeNumber`에 "번" 붙이기 (4.2)
7. Vercel Preview 또는 추가 배포 도메인을 사용할 경우 Origin 공유
8. `destinationStops` 초기 목록 구성에 원하는 바가 있으면 회신
