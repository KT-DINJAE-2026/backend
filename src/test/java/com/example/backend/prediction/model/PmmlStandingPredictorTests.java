package com.example.backend.prediction.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.prediction.feature.PredictionModelInput;

import org.junit.jupiter.api.Test;

/**
 * 테스트용 소형 PMML로 추론 경로 전체를 검증한다.
 *
 * <p>픽스처 분기: 학습 노선이면서 {@code hour >= 8}이면 입석 확률 0.8·입석시간 360초,
 * 그 외에는 입석 확률 0.1이다.</p>
 */
class PmmlStandingPredictorTests {

	private final StandingPredictor predictor = new PmmlStandingPredictor(TestStandingModels.load());

	@Test
	void 입석으로_판정하면_입석시간까지_산출한다() {
		StandingPrediction prediction = predictor.predict(input("100100129", "107000087", 9));

		assertThat(prediction.status()).isEqualTo(StandingPredictionStatus.PREDICTED);
		assertThat(prediction.standing()).isTrue();
		assertThat(prediction.standingProbability()).isEqualTo(0.8d);
		assertThat(prediction.standingSeconds()).isEqualTo(360.0d);
	}

	@Test
	void 미입석으로_판정하면_입석시간을_계산하지_않는다() {
		// 미입석 승객의 입석시간은 학습 대상이 아니어서 모델 B를 실행할 이유가 없다.
		StandingPrediction prediction = predictor.predict(input("100100129", "107000087", 6));

		assertThat(prediction.status()).isEqualTo(StandingPredictionStatus.PREDICTED);
		assertThat(prediction.standing()).isFalse();
		assertThat(prediction.standingProbability()).isEqualTo(0.1d);
		assertThat(prediction.standingSeconds()).isNull();
	}

	@Test
	void 학습_범위_밖_노선은_추론하지_않고_범위_밖으로_알린다() {
		// PMML이 asMissing으로 선언되어 있어 그냥 넣으면 오류 없이 값이 나오지만 근거가 없다.
		StandingPrediction prediction = predictor.predict(input("999999999", "107000087", 9));

		assertThat(prediction.status()).isEqualTo(StandingPredictionStatus.OUT_OF_DOMAIN);
		assertThat(prediction.standingProbability()).isNull();
		assertThat(prediction.standing()).isNull();
	}

	@Test
	void 학습_범위_밖_정류장도_범위_밖으로_알린다() {
		StandingPrediction prediction = predictor.predict(input("100100129", "121009999", 9));

		assertThat(prediction.status()).isEqualTo(StandingPredictionStatus.OUT_OF_DOMAIN);
	}

	@Test
	void 범주_코드_매핑값을_넣으면_학습_범위_밖으로_걸러진다() {
		/*
		 * AI팀은 매핑 JSON의 정수 코드로 변환해 입력하라고 안내했지만 PMML은 원본 ID를 참조한다.
		 * 코드 31(=노선 100100129)을 넣는 실수가 다시 들어오면 이 테스트가 잡는다.
		 */
		StandingPrediction prediction = predictor.predict(input("31", "107000087", 9));

		assertThat(prediction.status()).isEqualTo(StandingPredictionStatus.OUT_OF_DOMAIN);
	}

	@Test
	void 모델을_적재하지_않았으면_예측_대신_모델_없음을_알린다() {
		StandingPredictor unavailable = new PmmlStandingPredictor(new StandingModels(null, null));

		StandingPrediction prediction = unavailable.predict(input("100100129", "107000087", 9));

		assertThat(prediction.status()).isEqualTo(StandingPredictionStatus.MODEL_UNAVAILABLE);
		assertThat(prediction.isPredicted()).isFalse();
	}

	@Test
	void 배차_간격이_없어도_예측한다() {
		StandingPrediction prediction = predictor.predict(new PredictionModelInput(
				"100100129", "107000087", "107000089", "월", "맑음", 9, false, null
		));

		assertThat(prediction.status()).isEqualTo(StandingPredictionStatus.PREDICTED);
		assertThat(prediction.standing()).isTrue();
	}

	private static PredictionModelInput input(String routeId, String boardStopId, int hour) {
		return new PredictionModelInput(
				routeId, boardStopId, "107000089", "월", "맑음", hour, false, 420L
		);
	}
}
