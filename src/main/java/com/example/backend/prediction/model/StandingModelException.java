package com.example.backend.prediction.model;

/** 입석 예측 모델의 적재·실행 실패를 원인별로 구분한다. */
public class StandingModelException extends RuntimeException {

	private final Reason reason;

	public StandingModelException(Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public StandingModelException(Reason reason, String message, Throwable cause) {
		super(message, cause);
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}

	public enum Reason {
		/** 모델 비활성화, 경로 미설정 또는 모델 파일 없음. */
		CONFIGURATION,
		/** PMML을 읽을 수 없거나 기대한 입력·출력 계약과 다름. */
		LOAD_FAILURE,
		/** 적재된 모델의 추론 실행 실패. */
		EVALUATION_FAILURE
	}
}
