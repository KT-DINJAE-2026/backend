package com.example.backend.prediction;

/**
 * 여정 분석의 정상 HTTP 응답 상태이다.
 * {@link #INSUFFICIENT_DATA}도 오류가 아니며 가능한 도착·이동시간은 함께 반환한다.
 */
public enum JourneyStatus {
	SUCCESS,
	INSUFFICIENT_DATA
}
