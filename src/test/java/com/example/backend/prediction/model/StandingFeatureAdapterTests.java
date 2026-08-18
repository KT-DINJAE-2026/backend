package com.example.backend.prediction.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.example.backend.prediction.feature.PredictionModelInput;

import org.junit.jupiter.api.Test;

class StandingFeatureAdapterTests {

	@Test
	void 표준_ID는_문자열이_아니라_정수로_변환한다() {
		// PMML이 route_id·정류장 ID를 dataType="integer"로 선언하고 있어 문자열로 넣으면 매칭되지 않는다.
		Map<String, Object> features = StandingFeatureAdapter.toModelFeatures(input(8, false, 420L));

		assertThat(features.get("route_id")).isEqualTo(100100129);
		assertThat(features.get("board_stop_id")).isEqualTo(107000087);
		assertThat(features.get("alight_stop_id")).isEqualTo(107000089);
	}

	@Test
	void 요일과_날씨는_한글_원본값을_그대로_넣는다() {
		// AI팀은 매핑 JSON의 정수 코드로 바꾸라고 안내했지만 PMML은 한글 문자열을 열거하고 있다.
		Map<String, Object> features = StandingFeatureAdapter.toModelFeatures(input(8, false, 420L));

		assertThat(features.get("weekday")).isEqualTo("월");
		assertThat(features.get("weather")).isEqualTo("맑음");
	}

	@Test
	void 수치형_피처는_연속형_double로_변환한다() {
		Map<String, Object> features = StandingFeatureAdapter.toModelFeatures(input(9, true, 420L));

		assertThat(features.get("hour")).isEqualTo(9.0d);
		assertThat(features.get("is_holiday")).isEqualTo(1.0d);
		assertThat(features.get("headway_sec")).isEqualTo(420.0d);
	}

	@Test
	void 평일은_is_holiday를_0으로_넣는다() {
		Map<String, Object> features = StandingFeatureAdapter.toModelFeatures(input(9, false, 420L));

		assertThat(features.get("is_holiday")).isEqualTo(0.0d);
	}

	@Test
	void 배차_간격이_없으면_결측으로_남긴다() {
		// headway_sec는 PMML에서 결측을 허용하므로 임의의 기본값으로 채우지 않는다.
		Map<String, Object> features = StandingFeatureAdapter.toModelFeatures(input(9, false, null));

		assertThat(features).containsKey("headway_sec");
		assertThat(features.get("headway_sec")).isNull();
	}

	@Test
	void 여덟_개_피처를_모델_입력_순서대로_만든다() {
		Map<String, Object> features = StandingFeatureAdapter.toModelFeatures(input(9, false, 420L));

		assertThat(features.keySet()).containsExactlyElementsOf(PredictionModelInput.FEATURE_NAMES);
	}

	private static PredictionModelInput input(int hour, boolean holiday, Long headwaySec) {
		return new PredictionModelInput(
				"100100129", "107000087", "107000089", "월", "맑음", hour, holiday, headwaySec
		);
	}
}
