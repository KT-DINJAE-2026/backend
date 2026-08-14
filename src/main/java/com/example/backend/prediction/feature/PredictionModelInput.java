package com.example.backend.prediction.feature;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.example.backend.weather.ModelWeatherCategory;

/** AI팀이 전달한 모델 A/B에 공통으로 입력할 8개 원본 피처를 표현한다. */
public record PredictionModelInput(
		String routeId,
		String boardStopId,
		String alightStopId,
		String weekday,
		String weather,
		int hour,
		boolean holiday,
		Long headwaySec
) {

	public static final List<String> FEATURE_NAMES = List.of(
			"route_id",
			"board_stop_id",
			"alight_stop_id",
			"weekday",
			"weather",
			"hour",
			"is_holiday",
			"headway_sec"
	);

	public PredictionModelInput {
		requireText(routeId, "routeId");
		requireText(boardStopId, "boardStopId");
		requireText(alightStopId, "alightStopId");
		requireText(weekday, "weekday");
		if (weather != null && !ModelWeatherCategory.supports(weather)) {
			throw new IllegalArgumentException("weather는 모델이 학습한 다섯 범주 중 하나여야 합니다.");
		}
		if (hour < 0 || hour > 23) {
			throw new IllegalArgumentException("hour는 0부터 23 사이여야 합니다.");
		}
		if (headwaySec != null && headwaySec < 0) {
			throw new IllegalArgumentException("headwaySec는 0 이상이어야 합니다.");
		}
	}

	/**
	 * 범주 코드 변환 전 원본값을 PMML 필드 순서대로 제공한다.
	 *
	 * <p>전달받은 PMML과 매핑 JSON의 설명이 아직 일치하지 않으므로 여기서는 표준 ID와 한글 범주를
	 * 보존한다. 최종 변환은 golden test로 입력 방식을 확정한 뒤 PMML 어댑터에서 담당한다.</p>
	 */
	public Map<String, Object> asRawFeatureMap() {
		Map<String, Object> features = new LinkedHashMap<>();
		features.put("route_id", routeId);
		features.put("board_stop_id", boardStopId);
		features.put("alight_stop_id", alightStopId);
		features.put("weekday", weekday);
		features.put("weather", weather);
		features.put("hour", hour);
		features.put("is_holiday", holiday);
		features.put("headway_sec", headwaySec);
		return Collections.unmodifiableMap(features);
	}

	private static void requireText(String value, String name) {
		if (Objects.requireNonNull(value, name + "는 null일 수 없습니다.").isBlank()) {
			throw new IllegalArgumentException(name + "는 비어 있을 수 없습니다.");
		}
	}
}
