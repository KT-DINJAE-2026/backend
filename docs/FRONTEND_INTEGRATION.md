# 프론트엔드 연동 가이드

백엔드 API 사용법과 기존 FE Mock JSON 대비 변경 사항을 정리한 문서입니다.

- 기준일: 2026-08-03
- 기본 경로: `/api/v1`
- 데이터 형식: `application/json; charset=UTF-8`
- 인증: 없음

아래 응답은 실제 demo 서버 응답을 기준으로 작성했으며, 설명에 불필요한 배열 항목은 일부 생략했습니다. 생략된 예시는 해당 위치에 별도로 표시합니다.

현재 연동 규격의 기준은 **백엔드 Swagger와 이 문서**입니다. FE 저장소의 `docs/API_CONTRACT_DRAFT.md` 문서 버전 `0.6`과 Mock JSON은 최초 제안안이므로, 필드가 다를 때는 이 문서의 4장을 기준으로 맞춰 주세요.

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

백엔드는 **직통 노선이 없을 때 `404 NO_DIRECT_ROUTE`** 를 반환합니다. 지금 코드로는 `code`를 읽을 수 없어 "직통 버스 없음" 화면 대신 "서버 오류" 화면이 뜹니다. Figma의 `04-C-no-direct-route` 화면에 도달하려면 이 수정이 필요합니다.

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

`http://localhost:8080/swagger-ui.html` 에서 세 엔드포인트와 성공·데이터부족 예시를 확인할 수 있습니다. 현재 `NO_DIRECT_ROUTE`는 오류 스키마까지만 등록되어 있고 구체적인 JSON 예시는 이 문서 3.3절에 있습니다. Swagger에도 해당 예시를 추가하는 작업은 6장의 백엔드 잔여 항목으로 관리합니다.

### CORS

`/api/**` 경로에 `app.cors.allowed-origins`로 설정한 Origin의 `GET`, `POST`, `OPTIONS`를 허용합니다. 허용 헤더는 `Accept`, `Content-Type`이며 기본 Origin은 아래 두 주소입니다.

- `http://localhost:5173`
- `https://kd-dinjae-2026-fe.vercel.app`

정식 Vercel 주소는 이미 등록되어 있습니다. Vercel Preview URL이나 추가 배포 도메인을 사용할 경우 해당 Origin을 별도로 공유해 주세요. 활성 프로필의 `application-{profile}.yaml` 또는 외부 설정 파일에서 다음처럼 목록을 재정의하고 서버를 재시작하면 되며, 백엔드 재빌드는 필요하지 않습니다.

```yaml
app:
  cors:
    allowed-origins:
      - "https://preview.example.com"
      - "https://kd-dinjae-2026-fe.vercel.app"
```

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

검색 API는 직통 노선이 없는 정류장도 결과에서 숨기지 않고 `servedRoutes: []`로 반환합니다. 그 정류장으로 여정 분석을 요청하면 `404 NO_DIRECT_ROUTE`가 옵니다.

검색 결과는 최대 20개입니다. 정확한 노선 번호를 검색하면 해당 출발 정류장에서 그 노선을 타고 이후에 도착할 수 있는 정류장을 노선 순서대로 반환합니다. 정류장명이나 ARS 번호 검색은 전체 일치 정류장을 대상으로 하므로 직통 노선이 없는 결과도 포함될 수 있습니다.

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

서버는 구간 순서·연속성, 구간 시간 합계 = `travelMinutes`, `RELAXED` 제외 구간 합계 = `standingBurdenMinutes`, 한 응답 내 `tripId` 유일성을 지킵니다.

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

`standingBurdenMinutes`, `standingBurdenLevel`, `segments`는 `null`이 아니라 **필드 자체가 빠집니다.** 현재 API는 선택 값이 없을 때 `null` 대신 필드를 생략합니다.

#### 오류

```json
{ "code": "NO_DIRECT_ROUTE", "message": "두 정류장을 잇는 직통 노선이 없습니다.", "traceId": "166f068f" }
```

| HTTP | `code` | 상황 |
|---|---|---|
| `400` | `INVALID_REQUEST` | 필수값 누락, ID 형식 오류(9자리 아님), 파라미터 타입 불일치 |
| `404` | `RESOURCE_NOT_FOUND` | 존재하지 않는 API 경로 |
| `404` | `STOP_NOT_FOUND` | 출발 또는 도착 정류장 없음 |
| `404` | `NO_DIRECT_ROUTE` | 두 정류장을 잇는 직통 버스 없음 |
| `405` | `METHOD_NOT_ALLOWED` | 엔드포인트가 허용하지 않는 HTTP method |
| `409` | `STOP_DIRECTION_MISMATCH` | 노선은 지나지만 그 방향으로는 이동 불가 |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | 지원하지 않는 `Content-Type` |
| `502` | `UPSTREAM_FAILURE` | TOPIS 통신 실패 또는 해석할 수 없는 응답 |
| `503` | `UPSTREAM_UNAVAILABLE` | TOPIS 인증 실패 또는 서버 설정 누락 |
| `500` | `INTERNAL_SERVER_ERROR` | 서버 처리 실패 |

`message`는 사용자 안내에 쓰지 말고 로그 확인용으로만 쓰세요. `traceId`로 서버 로그와 대조할 수 있습니다.

---

## 4. 기존 FE Mock 응답 대비 변경 사항

### 4.1 백엔드 제공 데이터와 화면 표시 책임

현재 백엔드는 정류장·노선의 식별값과 원본명, 방향, 좌표, 도착·이동시간, 상태 enum 등 **서비스 판단에 필요한 값**을 제공합니다. FE는 이 값을 화면에 어떻게 표현할지 결정합니다.

이 구분은 기술적으로 반드시 정해진 규칙은 아닙니다. 현재 API에서는 자주 바뀌는 UI 문구를 FE가 관리하도록 구현한 것입니다. 실제 데이터가 필요한 필드는 FE가 임의로 만들지 않습니다.

| 기존 Mock 필드 | 현재 처리 방법 | 담당 |
|---|---|---|
| `displayName` | `stopName`을 그대로 사용하거나 화면용으로 가공 | FE |
| `landmark` | 현재 기획 범위에서 제외. 추후 실제 주변 장소 데이터가 필요하면 백엔드 데이터로 추가 | 현재 미사용 |
| `searchKeywords` | 제거. `/api/v1/stops/search`에서 정류장명·ARS·노선 번호 검색 | BE |
| `roadviewFallback` | 현재 API에서 제외. 대체 이미지가 필요하면 FE 자산으로 관리 | FE 선택 |
| `summaryMessage` | `standingBurdenLevel`, 도착·이동시간을 조합해 화면 문구 생성 | FE |
| `segments[].description` | `congestionLevel`에 대응하는 화면 문구 생성 | FE |
| `predictionBasis.description` | 현재는 `confidence` 라벨만 FE에서 생성. 실제 기준 시각·요일·날씨 등 근거가 필요하면 백엔드가 구조화된 값으로 추가 | FE 표시 / BE 사실값 |
| `fromStopDisplayName` / `toStopDisplayName` | `fromStopName` / `toStopName`을 사용하거나 화면용으로 가공 | FE |
| `dataSource` | 현재 API에서 제외. 추후 출처 메타데이터가 필요하면 백엔드 응답으로 추가 | 현재 미사용 |

예를 들어 백엔드는 `standingBurdenLevel: "HIGH"`, `congestionLevel: "RELAXED"`처럼 의미가 고정된 값을 보냅니다. FE는 이를 각각 `입석 부담 높음`, `여유`처럼 화면에 표시합니다. 반면 `평일 오후 2시 자료 기준`처럼 실제 예측 근거를 설명하는 정보는 FE가 만들지 않고 향후 백엔드가 제공해야 합니다. 문구를 백엔드에서 통합 관리하기로 다시 결정한다면 API에 표시 문구 필드를 추가할 수도 있습니다.

`predictionBasis`는 `confidence` 하나만 있는 객체입니다.

```json
"predictionBasis": { "confidence": "MEDIUM" }
```

### 4.2 `routeNumber`에 "번"이 붙지 않습니다

기존 Mock과 API 초안에 `1014번`, `1014` 표기가 섞여 있었습니다. **서버는 모든 응답에서 "번" 없이 `"1014"`로 통일**합니다. 화면 표시용 "번"은 FE에서 붙여주세요.

### 4.3 `NO_DIRECT_ROUTE`는 `404`입니다

기존 초안에는 `200 + servedRoutes: []`와 `404 NO_DIRECT_ROUTE`가 함께 있어 혼동될 수 있었습니다. 두 응답은 대상 요청이 다릅니다.

- **검색 API**: 직통이 없어도 정류장을 숨기지 않고 `200` + `servedRoutes: []`
- **여정 분석 API**: 그 정류장으로 요청하면 `404 NO_DIRECT_ROUTE`

이 차이를 화면에서 구분하려면 1장의 `busApi.js` 수정이 필요합니다.

### 4.4 좌표 정밀도가 소수점 8자리입니다

기존 Mock은 소수점 10자리(`37.5858514183`)였으나 서버는 8자리(`37.58585142`)로 반환합니다. 약 1mm 차이라 카카오 로드뷰 조회에는 영향이 없습니다.

### 4.5 `generatedAt`에 나노초가 포함됩니다

`2026-08-03T20:44:54.1033165+09:00` 형식입니다. ISO 8601이라 `new Date()`로 그대로 파싱됩니다.

### 4.6 입력 검증이 추가됐습니다

`stopId`·`originStopId`·`destinationStopId`는 숫자 9자리, `query`는 1~50자입니다. 위반 시 `400 INVALID_REQUEST`입니다.

### 4.7 `currentStop`에도 `directionDescription`이 있습니다

기존 Mock과 마찬가지로 `currentStop`에도 이 필드가 있으며, 값은 노선 종점명 기반으로 `"동묘앞 방면"` 형태로 생성됩니다.

---

## 5. 아직 실제 값이 아닌 것

현재 여정 분석 API는 **FE 계약 검증용 테스트 데이터**를 반환합니다. 현재 FE 연동에 사용할 응답 구조는 구현되어 있지만 값은 실제가 아닙니다.

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

## 6. FE 문서 대비 백엔드 구현 점검

FE 저장소의 `README.md`, `docs/API_CONTRACT_DRAFT.md`, `design/figma-import/README.md`와 실제 Mock JSON을 기준으로 확인했습니다.

### 6.1 현재 완료된 백엔드 범위

| FE 요구사항 | 현재 구현 | 판정 |
|---|---|---|
| QR의 `stopId`로 출발 정류장·ARS·원본명·방향·좌표 조회 | `GET /api/v1/stops/{stopId}/context` | 완료 |
| 정류장명·ARS·노선 번호 검색 | `GET /api/v1/stops/search` | 완료 |
| 직통 노선이 없는 검색 결과를 `servedRoutes: []`로 유지 | 검색 결과와 여정 분석 오류를 분리 | 완료 |
| 출발·도착을 순서대로 함께 지나는 직통 노선 판별 | 노선별 정류장 순서로 정방향·역방향 구분 | 완료 |
| 성공·데이터 부족·직통 없음 상태 구분 | `SUCCESS`, `INSUFFICIENT_DATA`, `NO_DIRECT_ROUTE` | 완료 |
| 데이터 부족 시 도착·이동시간 유지, 혼잡 필드 생략 | `200 INSUFFICIENT_DATA` 응답 | 완료 |
| 차량 단위 `tripId`, 구간 순서·연속성·시간 합계 | 실제 노선 순서를 사용해 응답을 생성 | 테스트 응답 기준 완료 |
| 잘못된 ID, 없는 정류장, 역방향, 직통 없음 오류 body | `code`, `message`, `traceId` 제공 | 핵심 흐름 완료 |
| 잘못된 경로·method·Content-Type·파라미터 타입의 HTTP 상태 | 각각 `404`, `405`, `415`, `400`과 공통 오류 body 제공 | 완료 |
| 로컬·정식 Vercel CORS | `app.cors.allowed-origins`의 Origin에 `GET`, `POST`, `OPTIONS` 허용 | 완료 |
| 로드뷰 | 백엔드는 WGS84 좌표만 제공하고 카카오 로드뷰는 FE가 처리 | 역할 범위 충족 |

따라서 **실시간 도착정보와 AI 예측을 제외한 현재 화면 연동은 demo 프로필로 진행할 수 있습니다.** QR 이미지 생성, 카카오 로드뷰 호출, enum의 사용자 표시 문구, 화면 정렬과 상태 컴포넌트는 FE 담당이며 백엔드 미구현으로 보지 않습니다.

### 6.2 백엔드에 남은 작업

| 우선순위 | 남은 작업 | 현재 영향 |
|---|---|---|
| 운영 전 필수 | TOPIS 도착정보를 여정 분석에 연결해 실제 `arrivalMinutes`, 차량별 `tripId`, 저상 여부를 반환 | 현재는 노선당 차량 1대의 고정 테스트 값만 반환 |
| 운영 전 필수 | AI 입력·출력 계약 확정 후 실제 이동시간·구간 혼잡도·입석 부담·신뢰도 연결 | 회의 전까지 의도적으로 보류 |
| 운영 전 필수 | 실제 예측 기준 시각과 요일·날씨·표본 등 근거가 필요하면 구조화된 필드로 정의 | 현재 `generatedAt`은 테스트 응답 생성 시각이고 `predictionBasis`는 `confidence`만 제공 |
| 운영 전 확인 | `directionDescription` 산정 기준 보완 | 현재 필드는 제공하지만 노선 종점명 중 첫 값을 사용하므로, 동명 정류장·여러 노선의 실제 승강장 방향을 정확히 나타내는지 검증 필요 |
| 운영 전 확인 | 검색 가능한 모든 정류장의 `arsId`, `location`, 방향 필수값 보장 | 현재 DB 모델은 ARS·좌표·노선 종점명을 nullable로 허용하므로 운영 데이터 누락 시 응답에서도 필드가 빠질 수 있음 |
| 연동 배포 전 | FE가 접근할 HTTPS 백엔드 주소 배포 및 환경변수 공유 | 현재는 로컬 `http://localhost:8080` 연결만 안내 |
| 문서 보완 | Swagger 404 응답에 `NO_DIRECT_ROUTE` JSON 예시 추가 | JSON 자체는 3.3절에서 확인 가능 |
| 배포 방식 확정 후 | Vercel Preview URL을 쓸 경우 `app.cors.allowed-origins`에 Origin 추가 | 정식 Vercel URL과 로컬 URL은 기본값으로 허용 |

TOPIS 클라이언트와 20초 캐시는 구현되어 있지만 현재 `JourneyTestDataService`가 이를 호출하지 않습니다. 따라서 “도착시간 갱신 주기와 캐시 기준”은 코드에 준비되어 있을 뿐 실제 여정 응답에 적용됐다고 보지 않습니다.

### 6.3 FE 초안에서 서로 다르게 적힌 부분

FE `docs/API_CONTRACT_DRAFT.md` 6장은 직통 노선이 없는 정류장도 검색 결과에 유지하라고 되어 있지만, 14장 체크 목록에는 검색 결과를 환승 없이 갈 수 있는 곳으로 제한할 수 있는지 확인한다고 적혀 있습니다. 현재 백엔드는 6장의 상세 규칙을 따릅니다.

- 정류장명·ARS 검색: 직통이 없어도 `servedRoutes: []`로 반환
- 정확한 노선 번호 검색: 그 노선으로 출발지 이후에 도착 가능한 정류장만 반환

이 방식이면 “검색 결과 없음”과 “정류장은 있지만 직통 버스 없음” 화면을 구분할 수 있습니다.

---

## 7. FE 요청 사항 정리

1. 🔴 `busApi.js`에서 오류 body를 파싱하고 `error.code` 보존 (1.1)
2. 🔴 `NO_DIRECT_ROUTE` 등 오류 코드별 화면 분기 구현 (1.1)
3. 🔴 `/api/v1/stops/search` 호출 함수와 검색 결과 상태 연결 (1.2)
4. `searchKeywords` 제거 또는 기본 빈 배열 처리 (1.3)
5. 빠진 문구 필드들을 FE에서 enum 기반으로 생성 (4.1)
6. `routeNumber`에 "번" 붙이기 (4.2)
7. Vercel Preview 또는 추가 배포 도메인을 사용할 경우 Origin 공유
8. `destinationStops` 초기 목록 구성에 원하는 바가 있으면 회신
