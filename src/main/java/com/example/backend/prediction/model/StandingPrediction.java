package com.example.backend.prediction.model;

/**
 * 차량 한 대에 대한 입석 예측 결과다.
 *
 * <p>예측하지 못한 경우 확률과 입석시간은 {@code null}이다. 호출자는 {@link #isPredicted()}를 먼저
 * 확인해야 하며, 근거 없는 값을 0으로 채워 응답하면 안 된다.</p>
 *
 * @param standingProbability 입석 확률. 모델 A의 {@code probability(1)}
 * @param standing 확률이 임계값 이상인지 여부
 * @param standingSeconds 입석 지속시간(초). 입석이 아니면 {@code null}
 */
public record StandingPrediction(
		StandingPredictionStatus status,
		Double standingProbability,
		Boolean standing,
		Double standingSeconds
) {

	public static StandingPrediction seated(double standingProbability) {
		return new StandingPrediction(StandingPredictionStatus.PREDICTED, standingProbability, false, null);
	}

	public static StandingPrediction standing(double standingProbability, double standingSeconds) {
		return new StandingPrediction(
				StandingPredictionStatus.PREDICTED,
				standingProbability,
				true,
				standingSeconds
		);
	}

	public static StandingPrediction unavailable(StandingPredictionStatus status) {
		if (status == StandingPredictionStatus.PREDICTED) {
			throw new IllegalArgumentException("예측에 성공한 결과는 seated 또는 standing으로 만들어야 합니다.");
		}
		return new StandingPrediction(status, null, null, null);
	}

	public boolean isPredicted() {
		return status == StandingPredictionStatus.PREDICTED;
	}
}
