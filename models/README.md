# 입석 예측 모델 파일

AI팀에서 전달받은 PMML 모델과 부속 파일을 보관한다. 배포 시 이 디렉터리를 Docker 이미지의
`/models`로 복사하고 `ML_MODEL_DIR` 환경변수로 위치를 주입한다(`docs/CLOUD_ARCHITECTURE.md` 참고).

- 전달일: 2026-08-24, **배포용 최종본** (PMML `Header/Timestamp` 기준 `2026-08-20T18:40:36Z`)
- 학습: 1~12월 전체 데이터, `n_estimators=3000`, `num_leaves=127`
- 변환기: JPMML-LightGBM 1.6-SNAPSHOT, PMML 4.4
- 크기: 두 파일 합계 약 259MB. 적재 6.6초, 상주 힙 약 767MiB 실측(2026-08-24, RealStandingModelSmokeTests
  로그). **테스트 JVM 힙 4g(`build.gradle`), Cloud Run 메모리 4GiB가 필요하다** (2GiB는 적재 중 파싱 피크로 OOM — 2026-08-24 배포 실측).

## 파일을 받는 방법

모델 파일은 용량이 커서 git에 포함하지 않는다(`.gitignore`에서 `models/*`를 제외하고 이 README만
커밋한다). AI팀이 공유한 파일 다섯 개를 이 디렉터리에 그대로 내려받으면 된다.

이 때문에 GitHub Actions처럼 저장소만 체크아웃하는 환경에서는 이미지 빌드가 실패한다. 현재 설계대로
로컬에서 `gcloud builds submit`으로 배포하면 로컬 디렉터리가 업로드되므로 문제가 없다. 나중에 배포를
CI로 자동화한다면 그때 모델 파일 전달 방법(GCS 등)을 먼저 정해야 한다.

| 파일 | 역할 | 추론에 사용 |
|---|---|---|
| `model_a.pmml` | 입석 여부 이진 분류 | 사용 |
| `model_b.pmml` | 입석 지속시간(초) 회귀 | 사용 |
| `category_code_mapping_model_a.json` | 범주값 → 정수 코드 표 | **사용 안 함** (아래 참고) |
| `category_code_mapping_model_b.json` | 위와 바이트 단위로 동일한 파일 | **사용 안 함** |
| `golden_test_samples.json` | Python 원본 추론 기대값 20건 (common 10·rare 5·boundary 5) | 테스트에서 사용 |

## 검증된 입력 계약

두 모델의 `DataDictionary`와 `MiningSchema`를 직접 확인한 결과다. 모델 A와 B의 입력 8개는
이름·타입·순서가 완전히 동일하다.

| 피처 | optype | dataType | 넣어야 할 값 |
|---|---|---|---|
| `route_id` | categorical | integer | 국토부 표준 노선 ID를 **정수로** (예: `100100129`) |
| `board_stop_id` | categorical | integer | 표준 승차 정류장 ID를 정수로 |
| `alight_stop_id` | categorical | integer | 표준 하차 정류장 ID를 정수로 |
| `weekday` | categorical | string | 한글 요일 `월` `화` `수` `목` `금` `토` `일` |
| `weather` | categorical | string | `맑음` `구름많음` `흐림` `비` `눈` |
| `hour` | continuous | double | 0~23 |
| `is_holiday` | continuous | double | 공휴일 `1.0`, 평일 `0.0` |
| `headway_sec` | continuous | double | 배차 간격(초). **운영에서는 결측(NaN)으로 입력한다** — AI팀 권고(2026-08-24), 아래 "headway_sec 입력 방식" 참고 |

### 매핑 JSON을 쓰지 않는 이유

AI팀은 "범주형 5개를 매핑 JSON의 정수 코드로 변환해 입력하라"고 안내했지만, **실제 PMML은 코드가
아니라 원본값을 참조한다.** 근거는 `DataDictionary`에 열거된 값 자체다.

```xml
<DataField name="route_id" optype="categorical" dataType="integer">
    <Value value="100100006"/>   <!-- 매핑 코드 0 이 아니라 원본 노선 ID -->
```

`weekday`·`weather`도 `dataType="string"`에 한글 값이 그대로 열거되어 있어 정수 코드를 넣을 수 없다.
매핑 JSON대로 코드를 넣으면 예외 없이 전혀 다른 트리 분기로 떨어져 **조용히 틀린 예측**이 나온다.
따라서 매핑 JSON은 학습 과정 기록으로만 보관하고 추론 경로에서는 참조하지 않는다.

`route_id`가 `dataType="integer"`인 점은 주의해야 한다. 백엔드는 표준 ID를 문자열로 다루므로
PMML에 넘기기 직전에 정수로 변환해야 한다.

## 출력 계약

`model_a.pmml`은 LightGBM raw score(`lgbmValue`)를 logit으로 정규화하는 세그먼트가 붙어 있어
`probability(1)` 출력 필드로 **입석 확률**을 낸다. 입석 판정 임계값은 0.5다.

`model_b.pmml`은 `functionName="regression"`이고 별도 `Output`이나 역변환이 없다. 예측값이 곧
**입석 지속시간(초)**이다.

입석시간 5분 기준 `MEDIUM`/`HIGH` 변환, 음수·여정시간 초과 보정, FE 응답 조립은 백엔드가 담당한다.

## 모델 적용 범위

성북구 경유 노선만 학습했으므로 도메인 밖 입력은 예측할 수 없다.

| 항목 | 개수 |
|---|---|
| `route_id` | 94 |
| `board_stop_id` | 2,893 |
| `alight_stop_id` | 2,893 |

승차·하차 정류장 도메인은 크기는 같지만 구성이 다르다(`116000405`는 승차에만, `990070103`은
하차에만 존재). 두 집합을 하나로 합쳐 검사하면 안 되고 각각 확인해야 한다.

FE 데모 시나리오에 쓰는 정류장 `107000087`·`107000089`·`100000147`과 노선 `100100129`·`100100031`·
`100100008`·`100100021`은 모두 도메인 안에 있어 실제 추론으로 전환할 수 있다.

## 미학습 값 처리

범주형 4개는 `invalidValueTreatment="asMissing"`으로 선언되어 있어, 학습에 없던 노선·정류장 ID를
넣어도 예외가 아니라 결측으로 처리되고 예측은 그대로 나온다. 근거 없는 값이 사용자에게 나가지
않도록 **추론 전에 도메인 포함 여부를 백엔드가 먼저 검사해** `INSUFFICIENT_DATA`로 응답해야 한다.

`hour`·`is_holiday`·`headway_sec`는 `asIs`이며 `headway_sec`는 결측을 허용한다.

### headway_sec 입력 방식 — 결측(NaN) 확정 (2026-08-24 AI팀 답변)

학습의 `headway_sec`는 교통카드 태그 기반 실측 간격(직전 운행회차와의 간격)이며, golden 샘플의
7,894초·20,027초는 폭설 등으로 배차가 예외적으로 벌어진 실측 상위 1% 값이다. AI팀이 전달한
gain 기준 피처 중요도에서 **모델 A는 headway_sec 의존도가 약 72%(1위)**, hour 약 27%로 두
피처가 지배적이고, 모델 B는 약 2.3%(5위)다. 정의·분포가 다른 계획 배차간격을 넣으면 모델 A
판정이 왜곡될 수 있어, 학습 때 검증된 결측 경로(학습 결측률 1.03%, LightGBM 자체 처리)로
입력하는 것이 AI팀 권고다. 이에 따라 `app.headway.enabled` 기본값을 껐다
(`src/main/resources/headway/README.md`의 결정 기록 참고).

계획 배차간격 CSV·공급자는 보존한다. 운영과 같은 정의로 재학습한 모델을 받으면 되돌린다.

## 여정 API 연결 상태

`demo`가 아닌 프로필의 `/api/v1/journeys/predictions`는 TOPIS 차량별 도착 예정 시각으로
8개 피처를 생성해 이 PMML 예측기를 직접 호출한다. 학습 범위 밖 입력과 모델 미적재 상태는
실시간 도착·이동시간을 유지한 `INSUFFICIENT_DATA`로 변환한다. 2026-08-24부터 배포용 최종
모델이 연결되어 있으며 golden test로 Python 원본과의 동등성이 확인됐다.

## 모델 교체 시 재확인할 것

최종본을 받으면 위 계약이 유지되는지 다시 확인한다. 특히 변환기 설정이 바뀌면 범주 인코딩 방식이
달라질 수 있다.

- `DataDictionary`의 값이 여전히 원본 ID·한글인지 (코드로 바뀌었는지)
- 피처 8개의 이름·타입이 그대로인지
- `probability(1)`과 초 단위 회귀 출력이 유지되는지
- 노선·정류장 도메인 변화
- golden test 결과 일치 — `GoldenModelRegressionTests`가 `golden_test_samples.json`의 20건을
  자동 대조한다(확률 1e-4·초 0.02 허용 오차). 2026-08-24 최종본에서 전건 통과
