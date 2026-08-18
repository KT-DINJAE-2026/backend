package com.example.backend.prediction.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.example.backend.prediction.feature.PredictionModelInput;

/**
 * 백엔드의 원본 피처를 PMML이 선언한 자료형으로 바꾼다.
 *
 * <p><strong>범주 코드 매핑 JSON을 사용하지 않는다.</strong> AI팀 안내와 달리 전달된 PMML의
 * {@code DataDictionary}는 정수 코드가 아니라 원본 표준 ID와 한글 문자열을 값으로 열거하고 있다.
 * 매핑 코드를 넣으면 예외 없이 다른 트리 분기로 떨어져 조용히 틀린 예측이 나온다. 근거는
 * {@code models/README.md}에 정리해 두었다.</p>
 *
 * <p>표준 ID는 {@code dataType="integer"}로 선언되어 있어 문자열이 아니라 정수로 넘겨야 한다.</p>
 */
public final class StandingFeatureAdapter {

	private StandingFeatureAdapter() {
	}

	public static Map<String, Object> toModelFeatures(PredictionModelInput input) {
		Map<String, Object> features = new LinkedHashMap<>();
		features.put("route_id", toModelId(input.routeId()));
		features.put("board_stop_id", toModelId(input.boardStopId()));
		features.put("alight_stop_id", toModelId(input.alightStopId()));
		features.put("weekday", input.weekday());
		features.put("weather", input.weather());
		features.put("hour", (double) input.hour());
		features.put("is_holiday", input.holiday() ? 1.0d : 0.0d);
		features.put("headway_sec", input.headwaySec() == null ? null : input.headwaySec().doubleValue());
		return Collections.unmodifiableMap(features);
	}

	/**
	 * 표준 ID 문자열을 모델이 기대하는 정수로 바꾼다.
	 *
	 * <p>숫자가 아니거나 int 범위를 넘는 ID는 원본 문자열을 그대로 돌려준다. 정수 도메인에 존재할 수
	 * 없는 값이라 뒤따르는 범위 검사에서 반드시 걸러진다. 여기서 예외를 던지면 정상적인 범위 밖
	 * 요청이 500 오류가 되고, {@code null}을 돌려주면 결측을 허용하는 피처로 오인되어 근거 없는
	 * 예측이 나간다.</p>
	 */
	private static Object toModelId(String standardId) {
		try {
			return Integer.valueOf(standardId);
		} catch (NumberFormatException exception) {
			return standardId;
		}
	}
}
