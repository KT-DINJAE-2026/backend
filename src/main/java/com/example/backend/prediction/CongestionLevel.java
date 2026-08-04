package com.example.backend.prediction;

/** 구간별 혼잡도 단계. FE가 색상과 사용자 문구로 변환하는 안정적인 계약 값이다. */
public enum CongestionLevel {
	/** 좌석 여유 가능성이 높은 구간. */
	RELAXED,
	/** 보통 혼잡으로 입석 가능성이 있는 구간. */
	NORMAL,
	/** 혼잡한 구간. */
	CROWDED,
	/** 매우 혼잡한 구간. */
	VERY_CROWDED
}
