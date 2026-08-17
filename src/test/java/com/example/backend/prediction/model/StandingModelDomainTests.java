package com.example.backend.prediction.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import com.example.backend.prediction.feature.PredictionModelInput;

import org.junit.jupiter.api.Test;

class StandingModelDomainTests {

	private final StandingModelDomain domain = StandingModelDomain.from(
			TestStandingModels.evaluator(TestStandingModels.CLASSIFIER_RESOURCE)
	);

	@Test
	void 범주형_피처만_검사_대상으로_삼는다() {
		// hour·is_holiday·headway_sec는 연속형이라 열거된 값이 없다.
		assertThat(domain.sizes()).containsOnlyKeys(
				"route_id", "board_stop_id", "alight_stop_id", "weekday", "weather"
		);
		assertThat(domain.sizes().get("weekday")).isEqualTo(7);
		assertThat(domain.sizes().get("weather")).isEqualTo(5);
	}

	@Test
	void 학습_범위_안의_입력을_통과시킨다() {
		Map<String, Object> features = StandingFeatureAdapter.toModelFeatures(new PredictionModelInput(
				"100100129", "107000087", "107000089", "월", "맑음", 9, false, 420L
		));

		assertThat(domain.supports(features)).isTrue();
	}

	@Test
	void 결측을_허용하는_피처는_값이_없어도_통과시킨다() {
		Map<String, Object> features = new HashMap<>(StandingFeatureAdapter.toModelFeatures(
				new PredictionModelInput(
						"100100129", "107000087", "107000089", "월", "맑음", 9, false, null
				)
		));
		features.put("weather", null);

		assertThat(domain.supports(features)).isTrue();
	}

	@Test
	void 학습에_없는_노선을_걸러낸다() {
		Map<String, Object> features = StandingFeatureAdapter.toModelFeatures(new PredictionModelInput(
				"999999999", "107000087", "107000089", "월", "맑음", 9, false, 420L
		));

		assertThat(domain.supports(features)).isFalse();
	}

	@Test
	void 숫자가_아닌_ID는_정수_도메인에_없으므로_걸러낸다() {
		Map<String, Object> features = StandingFeatureAdapter.toModelFeatures(new PredictionModelInput(
				"ROUTE-A", "107000087", "107000089", "월", "맑음", 9, false, 420L
		));

		assertThat(domain.supports(features)).isFalse();
	}
}
