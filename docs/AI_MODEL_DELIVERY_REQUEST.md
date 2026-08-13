# AI 모델 전달 및 PMML 연동 요청서

Spring Boot 백엔드에서 LightGBM 모델을 직접 실행하기 위해 AI팀에 요청하는 최소 산출물과 확인 사항을 정리한 문서입니다.

- 기준일: 2026-08-07
- 문서 상태: AI팀 협의용 초안
- 추론 구조: LightGBM TXT → PMML 변환 → Spring Boot 직접 추론
- 모델 구성: 모델 A(입석 여부 분류) → 조건부 모델 B(입석 지속시간 회귀)

---

## 1. 배포 구조

운영 추론은 별도 FastAPI 서버를 호출하지 않고 Spring Boot 프로세스 안에서 수행합니다.

```text
AI팀
  └─ 최종 LightGBM TXT 모델, 실제 학습 내역, PMML 동등성 검증 자료 전달

백엔드
  ├─ TXT를 PMML로 변환
  ├─ 날씨·배차간격 등을 포함한 모델 입력 생성
  ├─ PMML 모델 A/B 실행
  ├─ 데이터 부족·위험도·구간별 사용자 입석 가능성 계산
  └─ 기존 FE 여정 응답으로 변환
```



---

## 2. 학습 데이터
### 2.1 컬럼 계약

| 컬럼 | 타입 | 백엔드가 정의한 의미 |
| --- | --- | --- |
| `trip_round_id` | string | 차량 ID와 추정 운행 회차 최초 승차 태그 시각을 조합한 회차 ID |
| `route_id` | string | 국토교통부 표준 노선 ID |
| `board_stop_id` | string | 승차 정류장 ID |
| `alight_stop_id` | string/null | 하차 정류장 ID. 하차 미태그는 `NULL` |
| `board_datetime` | timestamp | 실제 승차 태그 시각 |
| `weekday` | string | `월`, `화`, `수`, `목`, `금`, `토`, `일` |
| `hour` | integer | 승차 시각의 시간대 `0`~`23` |
| `is_holiday` | boolean | 대한민국 공휴일 여부 |
| `weather` | string/null | 승차 정류장·승차 시각 기준 과거 날씨 범주 |
| `headway_sec` | integer/null | 같은 노선·정류장의 직전 관측 회차와의 간격(초) |
| `bus_type_code` | string | TCD 버스 유형 코드 |
| `bus_type_name` | string | 버스 유형 이름 |
| `seat_capacity` | integer | 버스 유형에서 파생한 고정 좌석 수 |
| `usertype_code` | string/null | TCD 사용자 구분 코드 |
| `usertype_name` | string/null | 사용자 구분 이름 |
| `is_standing` | string | 승차 시점 입석 여부 `Y`/`N` |
| `standing_seconds` | integer/null | 입석 시작부터 최초 착석 또는 입석 상태 하차까지의 초 |
| `sample_count_route_segment_hour` | integer | `(route_id, board_stop_id, alight_stop_id, hour)`의 4월 전체 표본 수 |

### 2.2 파생값 정의

다음 값의 의미는 AI팀에 다시 정의를 요청하지 않고 백엔드 데이터 계약으로 사용합니다.

- `weekday`: `board_datetime`에서 생성한 한글 요일
- `hour`: `board_datetime`의 시간
- `is_holiday`: Python `holidays`의 대한민국 달력 기준 boolean
- `weather`: Open-Meteo 과거 WMO 코드를 `맑음`, `구름많음`, `흐림`, `안개`, `비`, `눈`, `뇌우`로 변환
- `headway_sec`: 현재 회차와 직전 회차가 해당 정류장에서 받은 최초 승차 태그 시각의 차이
- `seat_capacity`: 마을버스 20석, 간선·지선버스 25석
- `sample_count_route_segment_hour`: 노선·승하차 OD·시간대 기준 전체 기간 행 수

현재 4월 데이터에는 공휴일이 없어 `is_holiday`가 모두 `false`입니다. `headway_sec`는 실제 도착정보가 아니라 승차 태그로 추정한 값이고, 첫 관측 회차는 `NULL`입니다.

### 2.3 학습 레이블 정의

입석 레이블도 백엔드가 재차인원 복원과 FIFO 좌석배정으로 생성합니다.

- `is_standing=Y`: 승차 시점에 좌석이 없어 입석으로 배정된 승객
- `is_standing=N`: 승차 시점에 좌석에 앉은 승객
- `standing_seconds=0`: 승차 시점부터 착석한 승객
- `standing_seconds>0`: 승차 후 최초 착석까지의 시간. 착석하지 못하고 하차하면 승차부터 하차까지의 시간
- `standing_seconds=NULL`: 입석 승객의 하차 태그가 없어 지속시간을 확정할 수 없는 경우

이 레이블은 실측 좌석 센서값이 아니라 고정 좌석 수와 FIFO 가정으로 생성한 추정값입니다. AI팀은 레이블 의미를 변경하거나 다시 계산하지 않고 target으로 사용합니다.

---

## 3. 현재 AI 코드에서 확인한 학습 방식

현재 AI 레포의 `train_models.py`, `train_models_wandb.py`에서는 다음 동작을 확인했습니다.

- `roster_all.parquet`을 pandas로 직접 로드
- `alight_stop_id=NULL` 행 제외
- `headway_sec=NULL`은 LightGBM 결측치로 유지
- 범주형 피처를 pandas `category`로 변환
- 모델 A target: `is_standing == "Y"`를 `1`로 변환한 `y_standing`
- 모델 B 대상: `y_standing == 1`인 행
- `standing_seconds=NULL` 행은 `alight_stop_id=NULL` 제외 단계에서 함께 제거
- 별도의 수치 스케일링, 로그 변환, 언더샘플링·오버샘플링 또는 class weight 없음
- 모델 B는 `standing_seconds`를 초 단위 그대로 학습
- 모델 A/B 공통 피처 11개 사용

```text
route_id
board_stop_id
alight_stop_id
weekday
weather
bus_type_code
usertype_code
hour
is_holiday
headway_sec
seat_capacity
```

다만 다음 사항은 코드와 실험이 여러 버전이라 최종 모델 기준 확인이 필요합니다.

- `train_models.py`: 승객 행 단위 무작위 80/20 분할
- `train_models_wandb.py`: 2025-04-24 04:00 기준 시간 분할
- 모델 파일은 Git에 없고 W&B Artifact에만 보관
- 실험마다 하이퍼파라미터와 모델 파일이 다름

AI팀에는 이미 코드로 확인되는 내용을 다시 설명해 달라고 요청하지 않고, 전달할 최종 모델이 어느 학습 실행의 결과인지와 위 차이점의 최종 상태만 요청합니다.

---

## 4. 요청 산출물

| 파일 | 내용 |
| --- | --- |
| `model_a.txt` | 최종 LightGBM 입석 여부 이진 분류 모델 |
| `model_b.txt` | 최종 LightGBM 입석 지속시간 회귀 모델 |
| `feature_schema.json` | 최종 모델의 피처명, 순서, 자료형, 범주형 여부 |
| `categorical_values.json` | 최종 학습에 사용한 범주형 값과 pandas category 순서 |
| `training_data_usage.md` | 최종 모델이 사용한 추가 필터·변환·분할과 학습 실행 정보 |
| `golden_test_cases.json` | Python TXT 예측과 Java PMML 예측 비교 자료 |
| `metrics.json` | 최종 모델 A/B의 기본 평가 결과 |

권장 전달 구조는 다음과 같습니다.

```text
model-release-v1/
├── model_a.txt
├── model_b.txt
├── feature_schema.json
├── categorical_values.json
├── training_data_usage.md
├── golden_test_cases.json
└── metrics.json
```

`model_a.txt`, `model_b.txt`는 LightGBM `Booster.save_model()`로 생성한 네이티브 모델 파일이어야 합니다. 사용한 Python, LightGBM, pandas 버전도 `training_data_usage.md`에 적어주세요.

아래 JSON은 전달 형식을 맞추기 위한 권장 예시입니다. 현재 코드에서 확인한 11개 피처를 기준으로 작성했으며, 실제 전달 파일에는 최종 모델이 사용한 피처와 값을 빠짐없이 기록해야 합니다. 최종 모델이 이 예시와 다르면 예시가 아니라 최종 모델을 기준으로 작성합니다.

### 4.1 `feature_schema.json` 예시

`order`는 LightGBM 모델에 입력한 순서를 뜻합니다. 모델 A와 B의 피처가 다르면 `modelA`, `modelB`를 각각 작성합니다.

```json
{
  "schemaVersion": "1.0",
  "modelA": {
    "features": [
      { "order": 1, "name": "route_id", "type": "string", "categorical": true, "nullable": false },
      { "order": 2, "name": "board_stop_id", "type": "string", "categorical": true, "nullable": false },
      { "order": 3, "name": "alight_stop_id", "type": "string", "categorical": true, "nullable": false },
      { "order": 4, "name": "weekday", "type": "string", "categorical": true, "nullable": false },
      { "order": 5, "name": "weather", "type": "string", "categorical": true, "nullable": true },
      { "order": 6, "name": "bus_type_code", "type": "string", "categorical": true, "nullable": false },
      { "order": 7, "name": "usertype_code", "type": "string", "categorical": true, "nullable": true },
      { "order": 8, "name": "hour", "type": "integer", "categorical": false, "nullable": false, "minimum": 0, "maximum": 23 },
      { "order": 9, "name": "is_holiday", "type": "boolean", "categorical": false, "nullable": false },
      { "order": 10, "name": "headway_sec", "type": "number", "categorical": false, "nullable": true, "unit": "second" },
      { "order": 11, "name": "seat_capacity", "type": "integer", "categorical": false, "nullable": false, "unit": "seat" }
    ]
  },
  "modelB": {
    "sameAs": "modelA"
  }
}
```

### 4.2 `categorical_values.json` 예시

배열 순서는 최종 학습 당시 pandas category 순서와 같아야 합니다. 아래 노선·정류장·사용자 코드는 형식 설명을 위한 예시이며, 실제 파일에서는 생략하거나 축약하지 않고 학습에 사용한 전체 값을 전달합니다.

```json
{
  "schemaVersion": "1.0",
  "categories": {
    "route_id": ["100100001", "100100002"],
    "board_stop_id": ["100000001", "100000002"],
    "alight_stop_id": ["100000002", "100000003"],
    "weekday": ["월", "화", "수", "목", "금", "토", "일"],
    "weather": ["맑음", "구름많음", "흐림", "안개", "비", "눈", "뇌우"],
    "bus_type_code": ["105", "115", "120", "121", "122", "151"],
    "usertype_code": ["01", "02"]
  }
}
```

### 4.3 `golden_test_cases.json` 예시

Golden test의 상세 조건은 6절을 따릅니다. `standingSeconds`는 모델 A가 미입석으로 판정한 사례에서는 `null`로 표시합니다.

```json
{
  "schemaVersion": "1.0",
  "probabilityThreshold": 0.5,
  "cases": [
    {
      "caseId": "standing-medium-001",
      "features": {
        "route_id": "100100001",
        "board_stop_id": "100000001",
        "alight_stop_id": "100000003",
        "weekday": "화",
        "weather": "맑음",
        "bus_type_code": "115",
        "usertype_code": "01",
        "hour": 8,
        "is_holiday": false,
        "headway_sec": 420,
        "seat_capacity": 25
      },
      "expected": {
        "standingProbability": 0.72,
        "isStanding": true,
        "standingSeconds": 240.0
      }
    },
    {
      "caseId": "seated-001",
      "features": {
        "route_id": "100100002",
        "board_stop_id": "100000002",
        "alight_stop_id": "100000003",
        "weekday": "일",
        "weather": "비",
        "bus_type_code": "120",
        "usertype_code": "02",
        "hour": 14,
        "is_holiday": false,
        "headway_sec": null,
        "seat_capacity": 25
      },
      "expected": {
        "standingProbability": 0.18,
        "isStanding": false,
        "standingSeconds": null
      }
    }
  ],
  "tolerance": {
    "standingProbabilityAbsolute": 1e-6,
    "standingSecondsAbsolute": 1e-3
  }
}
```

### 4.4 `metrics.json` 예시

성능 수치는 형식 설명용 예시가 아니라 최종 모델 평가 실행에서 산출한 실제 값을 기록합니다. 데이터 기간과 분할 방식을 함께 적어야 같은 지표를 재현할 수 있습니다.

```json
{
  "schemaVersion": "1.0",
  "evaluation": {
    "splitMethod": "temporal",
    "train": { "from": "2025-04-01", "to": "2025-04-23", "rowCount": 0 },
    "validation": { "from": "2025-04-24", "to": "2025-04-26", "rowCount": 0 },
    "test": { "from": "2025-04-27", "to": "2025-04-30", "rowCount": 0 }
  },
  "modelA": {
    "rocAuc": 0.0,
    "prAuc": 0.0,
    "precision": 0.0,
    "recall": 0.0,
    "f1": 0.0
  },
  "modelB": {
    "maeSeconds": 0.0,
    "rmseSeconds": 0.0
  },
  "coverage": {
    "regions": ["서초구"],
    "routeCount": 0,
    "stopCount": 0
  }
}
```

---

## 5. AI팀 확인 요청 사항

다음은 백엔드 파이프라인만으로 확정할 수 없고 최종 모델 파일에 종속되는 항목입니다.

### 5.1 최종 모델 식별

- 최종 모델을 만든 학습 스크립트와 Git 커밋
- W&B 실행 또는 Artifact 식별자
- 학습 실행일과 주요 하이퍼파라미터
- 무작위 분할과 시간 분할 중 최종 적용 방식
- 학습·validation·test 기간과 행 수

### 5.2 실제 사용 피처

- 전달 모델 A/B의 최종 피처명과 순서
- 모델 A와 B가 같은 피처를 사용하는지
- 현재 확인된 11개 피처에서 추가·제거된 항목

`feature_schema.json`과 `categorical_values.json`이 최종 TXT 모델과 정확히 일치해야 합니다.

### 5.3 백엔드 출력 이후의 추가 처리

현재 확인한 학습 방식과 달라진 추가 처리가 있다면 그 내용만 알려주세요.

- `alight_stop_id=NULL` 외에 추가로 제외한 행
- 언더샘플링·오버샘플링·class weight 적용 여부
- `headway_sec` 또는 다른 수치 피처의 절삭·대체·스케일링 여부
- Parquet 컬럼을 다시 계산하거나 새 피처를 만든 경우
- `standing_seconds`에 로그 변환·단위 변환·상한 처리를 적용했는지

추가 처리가 없다면 `추가 처리 없음`이라고 명시하면 됩니다.

### 5.4 모델 출력 계약

백엔드는 다음 형식으로 모델을 실행하므로 전달 모델도 이 계약을 따라야 합니다.

- 모델 A 출력: `is_standing=Y`의 확률
- 모델 A 입석 판정: 확률 `0.5` 이상
- 모델 B 출력: 별도 역변환이 필요 없는 초 단위 입석 지속시간

최종 모델이 이 형식과 다르다면 모델 전달 전에 변경 내용을 알려주세요. 데이터 부족 기준으로 사용할 최소 표본 수는 모델 평가 결과를 근거로 권고해 주세요.

입석시간 5분 이하/초과에 따른 `MEDIUM`/`HIGH` 변환, 음수·전체 여정시간 초과 보정과 FE 응답 생성은 백엔드가 담당합니다.

---

## 6. Golden test

Python TXT 모델과 Java PMML 모델을 비교할 수 있도록 10~20건의 입력과 기대 결과를 제공해 주세요.

다음 사례를 포함합니다.

- 입석·미입석
- 입석 확률 임계값 전후
- 입석시간 5분 전후
- `headway_sec=NULL`
- 서로 다른 노선·요일·날씨·버스 유형

각 사례에는 다음 값이 필요합니다.

```json
{
  "caseId": "standing-medium-001",
  "features": {
    "route_id": "...",
    "board_stop_id": "...",
    "alight_stop_id": "...",
    "weekday": "화",
    "weather": "맑음",
    "bus_type_code": "115",
    "usertype_code": "01",
    "hour": 8,
    "is_holiday": false,
    "headway_sec": 420,
    "seat_capacity": 25
  },
  "expected": {
    "standingProbability": 0.72,
    "isStanding": true,
    "standingSeconds": 240
  }
}
```

부동소수점 수치는 작은 차이를 허용할 수 있지만 입석 여부와 분 단위 입석시간은 Python과 PMML 결과가 같아야 합니다.

---

## 7. 기본 평가 결과

`metrics.json`에는 다음 값만 포함하면 됩니다.

- 모델 A: ROC-AUC, PR-AUC, Precision, Recall, F1
- 모델 B: MAE, RMSE
- 학습·validation·test 기간과 행 수
- 모델이 지원하는 노선과 정류장 범위

---

## 8. 백엔드가 담당하는 운영 처리

다음은 AI팀 요청 항목이 아닙니다.

- 운영 시각에서 요일·시간·공휴일 계산
- Open-Meteo 날씨 조회와 학습 범주 변환
- TOPIS/TAGO 또는 과거 통계로 `headway_sec` 계산
- 학습에 없던 노선·정류장·범주 입력 처리
- `roster_all.parquet`에서 표본 수 조회용 데이터를 생성하고 관리
- 최소 표본 수에 따른 `INSUFFICIENT_DATA` 처리
- 모델 A/B 실행 순서와 위험도 변환
- 모델 B의 입석시간과 실제 구간 이동시간을 이용한 구간별 사용자 입석 가능성 계산
- FE API 응답 및 오류 형식 유지

구간별 값은 차량 전체 혼잡도가 아니라 사용자가 해당 정류장 구간에서 서 있을 가능성을 의미합니다. 별도의 차량 혼잡도 모델이나 정류장마다 반복하는 AI 추론은 요청하지 않습니다.

---

## 9. 전달 완료 기준

- [ ] 최종 모델 A/B TXT 파일이 전달됨
- [ ] 최종 모델을 생성한 학습 실행이 식별됨
- [ ] 모델 A/B 피처명·순서·자료형이 전달됨
- [ ] 범주형 값과 category 순서가 전달됨
- [ ] 백엔드 Parquet 이후 추가 필터·변환이 확인됨
- [ ] 전달 모델이 모델 A 확률·모델 B 초 단위 출력 계약을 만족함
- [ ] Golden test와 Python 기대 결과가 전달됨
- [ ] 기본 평가 결과가 전달됨

위 항목이 확인되면 백엔드에서 TXT → PMML 변환, 결과 동등성 검증과 Spring Boot 직접 추론 구현을 진행합니다.
