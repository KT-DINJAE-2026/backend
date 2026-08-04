package com.example.backend.prediction;

/** 예측 신뢰도. 실제 판정 기준은 AI 연동 회의 후 확정한다. */
public enum PredictionConfidence {
	HIGH,
	MEDIUM,
	LOW,
	/** 신뢰도를 계산할 표본이 부족함. */
	UNAVAILABLE
}
