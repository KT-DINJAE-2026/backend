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
| DB | MySQL (로컬은 Docker Compose로 자동 기동) |
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

Windows에서 저장소 상위 경로에 한글이 포함돼 Gradle 테스트 워커가 클래스 경로를 읽지 못하면 빌드 산출물만 ASCII 임시 경로로 바꿔 실행할 수 있습니다.

```powershell
.\gradlew.bat test -PcustomBuildDir="$env:TEMP\backend-gradle-build"
```

빌드 (실행 가능한 jar 생성 → `build/libs/`):

```bash
./gradlew build
```

## 프로젝트 구조

```
backend/
├── build.gradle          # 의존성·빌드 설정
├── compose.yaml          # 로컬 개발용 MySQL 컨테이너 정의
├── src
│   ├── main
│   │   ├── java/com/example/backend/
│   │   │   ├── config/                    # CORS, OpenAPI, 애플리케이션 설정
│   │   │   ├── domain/                    # 정류장·노선·경유 Entity
│   │   │   ├── error/                     # 공통 API 오류 응답
│   │   │   ├── masterdata/                # 기반정보 DAT 적재 배치
│   │   │   ├── stop/                      # 정류장 context/search API
│   │   │   └── BackendApplication.java   # 메인 클래스
│   │   └── resources/
│   │       ├── application.yaml          # 공통 설정
│   │       ├── application-local.yaml    # 로컬 프로필
│   │       └── application-prod.yaml     # 운영 프로필
│   └── test
│       └── java/com/example/backend/
└── gradlew               # Gradle Wrapper 실행 스크립트
```

## 설정 파일

- **[application.yaml](src/main/resources/application.yaml)** — 앱 설정. 로컬 개발에서는 Docker Compose Support가 DB 접속 정보를 자동 주입하므로 datasource 설정이 없는 것이 정상입니다. 운영 환경 배포 시에는 프로필(`application-prod.yml`)이나 환경 변수로 실제 DB 접속 정보를 주입합니다.
- **[compose.yaml](compose.yaml)** — 로컬 개발 전용 MySQL 8.4 정의. 여기 적힌 계정 정보는 로컬 컨테이너에서만 쓰이는 값입니다. 데이터는 `mysql-data` 볼륨에 보존됩니다. 호스트 포트는 동적으로 할당되므로, DB 클라이언트(DBeaver 등)로 직접 접속하려면 `docker ps`로 매핑된 포트를 확인하거나 포트를 `"3306:3306"`으로 고정하면 됩니다.

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

Swagger UI는 서버 실행 후 `http://localhost:8080/swagger-ui.html`에서 확인할 수 있습니다. API 오류는 `code`, `message`, `traceId` 형식으로 반환합니다.

## 서울시 버스 도착정보 연동

TOPIS `getArrInfoByRoute`를 사용해 정류소·노선·정류소 순번별 첫 번째와 두 번째 도착 예정 차량을 조회합니다. `traTime1/2`는 초 단위로 해석해 올림한 분 단위 도착시간을 만들고, `vehId1/2`는 차량 단위 `tripId`, `busType1/2`는 일반·저상·굴절버스 구분으로 변환합니다. 같은 정류소·노선·순번 조회는 기본 20초 동안 메모리에 캐시합니다.

인증키는 저장소에 커밋하지 않습니다. 공공데이터포털의 **일반 인증키(Encoding 또는 Decoding)** 값을 사용해 `.env.example`을 `.env`로 복사한 뒤 값을 입력합니다. 클라이언트가 형식을 판별해 Encoding 키의 이중 인코딩을 방지하며, `.env`는 Git에서 제외됩니다.

```properties
SEOUL_BUS_API_KEY=발급받은_일반_인증키
SEOUL_BUS_API_ENABLED=true
```

연결 제한시간은 3초, 응답 제한시간은 5초입니다. 운영 환경에서는 동일한 이름의 환경 변수나 비밀 관리 서비스로 주입합니다. 연동을 일시적으로 끄려면 `SEOUL_BUS_API_ENABLED=false`를 사용합니다.

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

현재 구현 범위는 인수인계 작업 순서의 1~4단계입니다. `POST /api/v1/journeys/predictions`는 TOPIS 도착정보와 AI 배치 사전계산 산출물을 결합하는 다음 단계에서 연결합니다.

## 컨벤션 메모

- 시크릿(운영 DB 비밀번호 등)은 커밋하지 않고 환경 변수 또는 `.env`(gitignore 처리됨)로 관리합니다.
- 배포용 컨테이너 이미지는 추후 `./gradlew bootBuildImage` 또는 Dockerfile로 생성할 예정입니다.
