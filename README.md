# backend

KT 디인재 프로젝트 백엔드 레포지토리

## 기술 스택

| 구분 | 내용 |
|---|---|
| Language | Java 21 (LTS) |
| Framework | Spring Boot 4.1.0 |
| Build | Gradle 9.5.1 (Wrapper 포함 — 별도 설치 불필요) |
| Web | Spring Web MVC (REST API) |
| ORM | Spring Data JPA (Hibernate) |
| DB | MySQL 8.4 (로컬은 Docker Compose로 자동 기동), H2 (테스트·스모크 전용) |
| API 문서 | springdoc-openapi 3.0.1 (Swagger UI) |
| 기타 | Bean Validation, Lombok, Spring Boot DevTools |

## 사전 요구 사항

- **JDK 21** — [Eclipse Temurin 21](https://adoptium.net/) 권장
  ```bash
  brew install --cask temurin@21
  ```
- **Docker** — 로컬 MySQL 컨테이너 실행에 필요 (Docker Desktop 또는 [OrbStack](https://orbstack.dev/))

Gradle은 설치할 필요 없습니다. 프로젝트에 포함된 Wrapper(`./gradlew`)가 지정된 버전을 자동으로 받아 사용합니다.

## 실행 방법

```bash
./gradlew bootRun
```

이 명령 하나로 끝납니다. `spring-boot-docker-compose` 의존성이 앱 시작 시 [compose.yaml](compose.yaml)의 **MySQL 컨테이너를 자동으로 띄우고 접속 정보까지 자동 주입**하므로, DB를 수동으로 실행하거나 datasource 설정을 작성할 필요가 없습니다. 앱 종료 시 컨테이너는 중지(stop)되며 삭제되지는 않습니다.

테스트 실행:

```bash
./gradlew test
```

Windows에서 저장소 상위 경로에 한글이 포함돼 Gradle 테스트 워커가 클래스 경로를 읽지 못하면 빌드 산출물만 ASCII 임시 경로로 바꿔 실행할 수 있습니다. 이전 빌드에 삭제된 테스트 클래스 정보가 남은 경우까지 정리하도록 `clean test`를 사용합니다.

```powershell
.\gradlew.bat clean test -PcustomBuildDir="$env:TEMP\backend-gradle-build"
```

빌드 (실행 가능한 jar 생성 → `build/libs/`):

```bash
./gradlew build
```

Windows 한글 경로에서 전체 빌드를 실행할 때도 같은 우회 옵션을 사용합니다.

```powershell
.\gradlew.bat clean build -PcustomBuildDir="$env:TEMP\backend-gradle-build"
```

## 프로젝트 구조

```
backend/
├── build.gradle          # 의존성·빌드 설정
├── compose.yaml          # 로컬 개발용 MySQL 컨테이너 정의
├── docs/                 # 협업·설계 문서
├── src
│   ├── main
│   │   ├── java/com/example/backend/
│   │   │   ├── arrival/                   # 서울시 TOPIS 도착정보 연동
│   │   │   ├── config/                    # CORS, OpenAPI, 애플리케이션 설정
│   │   │   ├── controller/                # 헬스 체크
│   │   │   ├── demo/                      # demo 프로필 초기 데이터
│   │   │   ├── domain/                    # 정류장·노선·경유 Entity
│   │   │   ├── error/                     # 공통 API 오류 응답
│   │   │   ├── masterdata/                # 기반정보 DAT 적재 배치
│   │   │   ├── prediction/                # 여정 예측 API (현재 테스트 데이터)
│   │   │   ├── repository/                # Spring Data JPA 리포지토리
│   │   │   ├── stop/                      # 정류장 context/search API
│   │   │   └── BackendApplication.java   # 메인 클래스
│   │   └── resources/
│   │       ├── application.yaml          # 공통 설정
│   │       ├── application-local.yaml    # 로컬 프로필 (기본값)
│   │       ├── application-demo.yaml     # FE 연결용 demo 프로필 (메모리 H2)
│   │       ├── application-h2.yaml       # H2 파일 스모크 프로필
│   │       └── application-prod.yaml     # 운영 프로필
│   └── test
│       └── java/com/example/backend/
└── gradlew               # Gradle Wrapper 실행 스크립트
```

## 설정 파일

- **[application.yaml](src/main/resources/application.yaml)** — 공통 설정. 활성 프로필을 지정하지 않으면 `local`이 적용됩니다. 로컬 개발에서는 Docker Compose Support가 DB 접속 정보를 자동 주입하므로 datasource 설정이 없는 것이 정상입니다. 운영 환경 배포 시에는 프로필(`application-prod.yaml`)이나 환경 변수로 실제 DB 접속 정보를 주입합니다.
- **[compose.yaml](compose.yaml)** — 로컬 개발 전용 MySQL 8.4 정의. 여기 적힌 계정 정보는 로컬 컨테이너에서만 쓰이는 값입니다. 데이터는 `mysql-data` 볼륨에 보존됩니다. 호스트 포트는 동적으로 할당되므로, DB 클라이언트(DBeaver 등)로 직접 접속하려면 `docker ps`로 매핑된 포트를 확인하거나 포트를 `"3306:3306"`으로 고정하면 됩니다.

### 애플리케이션 설정값

`app` 프리픽스로 묶여 있으며 [AppProperties](src/main/java/com/example/backend/config/AppProperties.java)에 정의돼 있습니다.

| 설정 | 기본값 | 설명 |
|---|---|---|
| `app.api.initial-destination-stop-ids` | `107000089`, `100000147` | `/stops/{stopId}/context`가 초기 도착 정류장으로 내려줄 ID 목록 |
| `app.cors.allowed-origins` | `http://localhost:5173`, 정식 Vercel 주소 | 브라우저의 `/api/**` 접근을 허용할 Origin 목록. 프로필 YAML에서 재정의 가능 |
| `app.master-data.import-enabled` | `false` | 기반정보 적재 실행 여부 |
| `app.master-data.city-name` | `서울특별시` | 적재 대상 시도 |
| `app.master-data.stop-file` / `route-file` / `route-stop-file` | 환경 변수 | 적재할 DAT 경로 |
| `app.topis.enabled` | `true` | TOPIS 연동 사용 여부 |
| `app.topis.base-url` | `http://ws.bus.go.kr/api/rest` | TOPIS 엔드포인트 |
| `app.topis.connect-timeout` / `request-timeout` | `3s` / `5s` | 연결·응답 제한시간 |
| `app.topis.cache-ttl` | `20s` | 도착정보 캐시 유지시간 |
| `app.holiday.enabled` | `true` | 한국천문연구원 공휴일 조회 사용 여부 |
| `app.holiday.base-url` | 한국천문연구원 특일 정보 URL | `getRestDeInfo` 서비스 기본 주소 |
| `app.holiday.service-key` | `SEOUL_BUS_API_KEY` | TOPIS와 공통으로 사용하는 공공데이터포털 일반 인증키 |
| `app.holiday.connect-timeout` / `request-timeout` | `3s` / `5s` | 공휴일 API 연결·응답 제한시간 |
| `app.holiday.cache-ttl` | `24h` | 연도별 공휴일 목록 캐시 유지시간 |
| `app.weather.enabled` | `true` | Open-Meteo 시간별 예보 사용 여부 |
| `app.weather.base-url` | `https://api.open-meteo.com/v1/forecast` | 운영 날씨 예보 API 주소 |
| `app.weather.connect-timeout` / `request-timeout` | `3s` / `5s` | 날씨 API 연결·응답 제한시간 |
| `app.weather.cache-ttl` | `15m` | 0.1도 격자·일자별 시간 예보 캐시 유지시간 |
| `app.demo.enabled` | `false` | demo 초기 데이터 적재 여부 (`demo` 프로필에서 `true`) |

운영 프로필은 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` 환경 변수를 사용합니다.

```bash
java -jar backend.jar --spring.profiles.active=prod
```

Docker/MySQL이 없는 환경에서는 실제 데이터 스모크 테스트에 H2 파일 프로필을 사용할 수 있습니다. 운영 환경에서는 이 프로필을 사용하지 않습니다.

```powershell
.\gradlew.bat bootRun --args='--spring.profiles.active=h2'
```

H2 프로필의 데이터 파일은 `.local/backend-smoke.mv.db`에 생성되며 Git에는 포함되지 않습니다.

## API

| Method | URL | 설명 |
|---|---|---|
| `GET` | `/health` | 서버 상태 확인 |
| `GET` | `/api/v1/stops/{stopId}/context` | QR 출발 정류장과 초기 도착 정류장 조회 |
| `GET` | `/api/v1/stops/search?originStopId=&query=` | 정류장명·ARS·노선번호 검색 및 직통 노선 조회 |
| `POST` | `/api/v1/journeys/predictions` | FE 계약 검증용 직통 여정 테스트 데이터 조회 |

`stopId`, `originStopId`, `destinationStopId`는 숫자 9자리, `query`는 1~50자입니다. 검색 결과가 없으면 `200`과 함께 `destinationStops`가 빈 배열로 반환됩니다. 직통 노선이 없는 정류장도 검색 결과에서 숨기지 않고 `servedRoutes: []`로 내려주며, 그 정류장으로 여정 예측을 요청했을 때 `404 NO_DIRECT_ROUTE`가 반환됩니다.

FE 연동 API 계약의 기준은 서버 실행 후 `http://localhost:8080/swagger-ui.html`에서 확인할 수 있는 Swagger 스키마와 예시입니다. 실행 방법과 환경 설정은 이 README를 따르며, API 오류는 `code`, `message`, `traceId` 형식으로 반환합니다.

| HTTP status | `code` | 사용 상황 |
|---|---|---|
| `400` | `INVALID_REQUEST` | 필수값 누락, 잘못된 ID 형식 |
| `404` | `RESOURCE_NOT_FOUND` | 존재하지 않는 API 경로 |
| `404` | `STOP_NOT_FOUND` | 출발 또는 도착 정류장 없음 |
| `404` | `NO_DIRECT_ROUTE` | 두 정류장을 한 번에 잇는 버스 없음 |
| `405` | `METHOD_NOT_ALLOWED` | 엔드포인트가 허용하지 않는 HTTP method |
| `409` | `STOP_DIRECTION_MISMATCH` | 정류장은 있지만 선택 방향으로 이동 불가 |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | 지원하지 않는 `Content-Type` |
| `502` | `UPSTREAM_FAILURE` | TOPIS 통신 실패 또는 잘못된 응답 |
| `503` | `UPSTREAM_UNAVAILABLE` | TOPIS 인증 또는 서버 설정 문제 |
| `500` | `INTERNAL_SERVER_ERROR` | 서버 처리 실패 |

혼잡도 데이터 부족은 오류가 아니라 `200` 응답의 `status: "INSUFFICIENT_DATA"`로 구분합니다.

### CORS

[CorsConfig](src/main/java/com/example/backend/config/CorsConfig.java)에서 `/api/**` 경로에 `app.cors.allowed-origins`로 설정한 Origin의 `GET`, `POST`, `OPTIONS` 요청을 허용합니다. 허용 헤더는 `Accept`, `Content-Type`입니다. 기본값은 아래 두 주소입니다.

- `http://localhost:5173`
- `https://kd-dinjae-2026-fe.vercel.app`

정식 Vercel 주소는 등록되어 있지만 Preview URL은 허용되지 않습니다. Preview 또는 추가 배포 도메인을 사용하면 활성 프로필의 `application-{profile}.yaml`이나 외부 설정 파일에서 목록을 재정의한 뒤 서버를 재시작하세요. Java 코드를 바꾸거나 재빌드할 필요는 없습니다.

```yaml
app:
  cors:
    allowed-origins:
      - "https://preview.example.com"
      - "https://kd-dinjae-2026-fe.vercel.app"
```

### FE 연결용 demo 프로필

AI·TOPIS 연동 방식이 확정되기 전에도 FE가 실제 HTTP 연결을 검증할 수 있도록 격리된 `demo` 프로필을 제공합니다. 메모리 H2에 프론트 Mock과 같은 성공·데이터 부족·직통 없음 시나리오를 넣고, 고정된 테스트 데이터를 반환합니다. 로컬 MySQL과 운영 설정에는 데이터를 기록하지 않습니다.

```powershell
.\gradlew.bat bootRun --args='--spring.profiles.active=demo'
```

서버가 실행되면 `http://localhost:8080/swagger-ui.html`에서 다음 시나리오를 호출할 수 있습니다.

| 시나리오 | 출발 정류장 | 도착 정류장 | 결과 |
|---|---|---|---|
| 테스트 성공 | `107000087` | `107000089` | `SUCCESS`, 1014·103·142·152번 테스트 여정 |
| 테스트 데이터 부족 | `107000087` | `100000147` | `INSUFFICIENT_DATA`, 도착·이동시간만 제공 |
| 직통 없음 | `107000087` | `121009999` | `404 NO_DIRECT_ROUTE` |

이 프로필의 값은 FE–BE 계약 확인 전용이며 AI 성능이나 실제 버스 운행 결과로 사용하지 않습니다. FE 서버 모드는 `VITE_API_MODE=server`, `VITE_API_BASE_URL=http://localhost:8080`을 사용합니다.

## 서울시 버스 도착정보 연동

TOPIS `getArrInfoByRoute`를 사용해 정류소·노선·정류소 순번별 첫 번째와 두 번째 도착 예정 차량을 조회합니다. `traTime1/2`는 초 단위로 해석해 올림한 분 단위 도착시간을 만들고, `vehId1/2`는 차량 단위 `tripId`, `busType1/2`는 일반·저상·굴절버스 구분으로 변환합니다. 같은 정류소·노선·순번 조회는 기본 20초 동안 메모리에 캐시합니다.

인증키는 저장소에 커밋하지 않습니다. 공공데이터포털의 **일반 인증키(Encoding 또는 Decoding)** 값을 사용해 `.env.example`을 `.env`로 복사한 뒤 값을 입력합니다. TOPIS와 한국천문연구원 공휴일 API 등 공공데이터포털 API는 모두 `SEOUL_BUS_API_KEY` 하나를 공통으로 사용합니다. 클라이언트가 형식을 판별해 Encoding 키의 이중 인코딩을 방지하며, `.env`는 Git에서 제외됩니다.

```properties
SEOUL_BUS_API_KEY=발급받은_일반_인증키
SEOUL_BUS_API_ENABLED=true
```

연결 제한시간은 3초, 응답 제한시간은 5초입니다. 운영 환경에서는 동일한 이름의 환경 변수나 비밀 관리 서비스로 주입합니다. 연동을 일시적으로 끄려면 `SEOUL_BUS_API_ENABLED=false`를 사용합니다.

현재 인증키 발급과 API 활용 신청은 완료됐지만 공공데이터포털에서 `SERVICE KEY IS NOT REGISTERED` 응답이 계속되어 실호출 검증은 대기 중입니다. 현재 여정 API는 TOPIS를 호출하지 않고 테스트 데이터를 반환합니다.

## 모델 입력 날씨 연동

운영 날씨는 출발 정류장 좌표와 TOPIS 도착시간으로 계산한 승차 예정 시각을 기준으로 Open-Meteo 시간별 `weather_code`를 조회합니다. 학습 파이프라인과 동일하게 좌표를 0.1도 격자로 맞추고, 격자·날짜별 24시간 예보를 15분 동안 캐시합니다. Open-Meteo 일반 예보 호출에는 공공데이터포털 인증키를 사용하지 않습니다.

PMML이 학습한 값만 전달하도록 WMO 코드를 `맑음`, `구름많음`, `흐림`, `비`, `눈`으로 변환합니다. 별도 학습 범주가 없는 안개는 `흐림`, 뇌우는 `비`로 합칩니다. 현재 demo 여정은 고정값을 유지하며, 실제 PMML 여정 서비스가 `PredictionModelInputFactory.createForStop()`을 호출할 때 날씨 조회가 사용됩니다.

## 기반정보 적재

`STTN`, `ROUTE`, `ROUTESTTN` 파일은 UTF-8, `|` 구분, 헤더 없음 형식으로 읽습니다. 서로 다른 날짜 파일을 섞지 말고 같은 기준일의 세 파일을 지정해야 합니다. 적재는 기본적으로 꺼져 있으며 명시적으로 활성화한 실행에서만 MySQL에 upsert합니다.

```powershell
$env:MASTER_STOP_FILE='C:\decompress\backend_project\260706_기반정보\STTN_4월\STTN_20250401.dat'
$env:MASTER_ROUTE_FILE='C:\decompress\backend_project\260706_기반정보\ROUTE_4월\ROUTE_20250401.dat'
$env:MASTER_ROUTE_STOP_FILE='C:\decompress\backend_project\260706_기반정보\ROUTESTTN_4월\ROUTESTTN_20250401.dat'
.\gradlew.bat bootRun --args='--app.master-data.import-enabled=true'
```

Docker/MySQL 없이 H2 스모크 DB에 적재할 때는 마지막 명령에 H2 프로필을 함께 지정합니다.

```powershell
.\gradlew.bat bootRun --args='--spring.profiles.active=h2 --app.master-data.import-enabled=true'
```

적재 테이블은 `stop`, `route`, `route_stop`입니다. 국토부 표준 `stopId`와 `routeId`를 기본 키로 사용합니다. 원천 로컬 ID는 제공기관 코드와 합쳐서 매핑하므로 서로 다른 기관이 같은 로컬 ID를 사용해도 충돌하지 않습니다. 기본 적재 범위는 `서울특별시`이며 `app.master-data.city-name`으로 바꿀 수 있습니다.

`route_stop.stop_order`로 출발 정류장보다 뒤에 있는 도착 정류장만 직통으로 판정합니다. 순환 노선에서 동일 정류장이 여러 번 등장해도 정방향 순서 쌍이 존재하면 직통으로 처리합니다.

## 성북구 학습 데이터 전처리

`D:\20250328_KDATA`의 헤더가 있는 18컬럼 `METROPOLITAN` TCD는 원본 ZIP을 변경하거나 압축 해제하지 않고 일자별로 처리합니다. `STTN`의 법정동코드와 `ROUTESTTN`을 이용해 성북구 정류장을 경유하는 버스 노선을 찾고, 재차인원 복원을 위해 그 노선의 전체 승객을 포함합니다.

```powershell
python data-pipeline\build_metropolitan_roster.py `
  "D:\20250328_KDATA\DATA_20240401.zip" `
  "C:\bus-standing-work\pilot\roster_20240401.parquet" `
  --standard-id-zip "D:\20250313_KDATA\DATA_20240401.zip" `
  --exclude-unmapped-standard-ids `
  --with-weather
```

대용량 결과는 Git·OneDrive 경로 밖에 저장합니다. 18컬럼 자료에 없는 표준 ID는 같은 날짜의 27컬럼 TCD를 읽어 엄격히 매핑하며, 누락이나 충돌이 있으면 생성을 중단합니다. `--with-weather`는 STTN 좌표를 기준으로 Open-Meteo 과거 시간별 날씨를 연결하고 로컬 캐시를 재사용합니다. 전체 사용법, 파일럿 통계와 남은 작업은 [KDATA METROPOLITAN TCD 전처리](data-pipeline/docs/METROPOLITAN_TCD_PIPELINE.md)를 참고합니다.

여러 날짜를 생성한 뒤에는 `finalize_metropolitan_dataset.py`로 일별 표본 수를 전체 학습기간 기준으로 다시 계산합니다. 이 후처리기는 전체 파일을 메모리에 합치지 않고 입력과 다른 디렉터리에 최종 Parquet을 생성합니다.

## 여정 테스트 데이터

현재 여정 API는 예측 테이블, 외부 날씨 API, AI 배치 파일을 사용하지 않습니다. 요청한 출발·도착 정류장 사이의 직통 노선을 기반으로 FE 계약 검증용 도착시간·이동시간·입석 부담 데이터를 반환합니다.

프론트 Mock의 `107000089` 목적지는 `SUCCESS`, `100000147` 목적지는 `INSUFFICIENT_DATA` 시나리오로 고정되어 있습니다. 그 밖의 직통 구간도 테스트 값으로 응답하며, 실제 운행 또는 AI 예측 결과로 사용하면 안 됩니다.

### 실제 예측 연동 계획

AI 입력·출력과 백엔드 연동 방식은 회의 후 확정합니다. 현재 코드에는 예측 테이블, AI CSV 적재, 외부 날씨 조회가 없으며 `JourneyTestDataService`가 테스트 응답을 담당합니다.

회의에서 다음 항목을 결정한 뒤 실제 연동을 구현합니다.

- 실시간 API 호출 또는 배치 사전계산 여부
- 노선·승하차 정류장·요일·시각·날씨 등 조회 키와 예측 단위
- 입석 부담 단계와 `INSUFFICIENT_DATA`, `confidence` 판정 기준
- 예측 결과 전달 형식과 갱신 주기
- 지원 지역·기간과 실제 데모 정류장

## 컨벤션 메모

- 시크릿(운영 DB 비밀번호 등)은 커밋하지 않고 환경 변수 또는 `.env`(gitignore 처리됨)로 관리합니다.
- 주석은 코드가 그대로 보여주는 동작을 반복하지 않고, 클래스 책임·외부 데이터 계약·예외 처리 이유·알고리즘 선택처럼 수정 시 반드시 알아야 할 의도를 설명합니다. 동작을 변경하면 관련 JavaDoc과 인라인 주석도 함께 갱신합니다.
- 배포용 컨테이너 이미지는 추후 `./gradlew bootBuildImage` 또는 Dockerfile로 생성할 예정입니다.
