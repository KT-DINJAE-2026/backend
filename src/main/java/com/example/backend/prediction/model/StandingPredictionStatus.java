package com.example.backend.prediction.model;

/** 입석 예측을 실제로 수행했는지, 못 했다면 왜인지를 구분한다. */
public enum StandingPredictionStatus {

	/** 모델이 정상적으로 확률과 입석시간을 산출했다. */
	PREDICTED,
	/**
	 * 노선·정류장이 모델 학습 범위 밖이다.
	 *
	 * <p>PMML이 범주형 피처를 {@code invalidValueTreatment="asMissing"}으로 선언하고 있어 그대로
	 * 넣어도 오류 없이 값이 나오지만 근거가 없다. 이 상태는 예측 대신 데이터 부족으로 응답해야 한다.</p>
	 */
	OUT_OF_DOMAIN,
	/** 모델 파일을 적재하지 않아 예측할 수 없다. */
	MODEL_UNAVAILABLE
}
