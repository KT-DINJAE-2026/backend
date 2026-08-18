# 성북구 다중 QR 시연

아래 QR은 같은 프론트 주소에 서로 다른 서울시 표준 `stopId`를 넣습니다. 사용자가 QR을 바꿔 찍으면 프론트가 해당 ID로 `GET /api/v1/stops/{stopId}/context`를 호출하고, 백엔드가 현재 정류장과 갈 수 있는 목적지를 다시 계산합니다.

| 파일 | 실제 정류장 | stopId / ARS |
|---|---|---|
| `01-donam-sungsin.svg` | 돈암사거리.성신여대입구 | `107000007` / `08007` |
| `02-seongbuk-office.svg` | 성북구청.성북경찰서 | `107000087` / `08177` |
| `03-bomun-station.svg` | 보문역 | `107000089` / `08179` |

브라우저에서 [`index.html`](index.html)을 열면 A4 한 장에 세 QR을 인쇄할 수 있습니다.

## 실행 조건

1. 배포 백엔드는 아래 주소를 사용합니다.

   `https://backend-827716553089.asia-northeast3.run.app`

2. 프론트는 `VITE_API_MODE=server`를 사용해야 합니다.
3. Vercel 시연은 `VITE_API_BASE_URL=https://backend-827716553089.asia-northeast3.run.app`, 로컬 백엔드 개발은 `http://localhost:8080`을 사용합니다.
4. Vercel 환경변수를 바꾼 뒤에는 새로 배포해야 빌드 결과에 반영됩니다.

QR은 운영 프론트 주소인 `https://kd-dinjae-2026-fe.vercel.app/`를 사용합니다. 운영 프론트는 2026-08-18 기준 Cloud Run API의 server 모드로 재배포되었고 세 QR 모두 정상 동작합니다.

현재 API 응답은 실제 AI 예측 연동 전의 계약 검증용 데모 데이터입니다. Cloud Run이 유휴 상태라면 첫 요청에 10초 이상 걸릴 수 있으므로 발표 전 한 번 접속하거나 상시 기동 전환 여부를 확인합니다.

## 데이터 범위

- 실제값: 정류장 ID, ARS 번호, 정류장명, 좌표, 노선 ID·번호, 정류장 경유 순서
- 시연값: 버스 도착 예정시간, 이동시간, 차량 종류, 입석 부담, 구간 혼잡도
- 기준 자료: 서울시 `버스정류소 위치정보(20260804)` 및 `버스노선별정류소정보(20260804)`
