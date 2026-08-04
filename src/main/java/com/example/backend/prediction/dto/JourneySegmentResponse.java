package com.example.backend.prediction.dto;

import com.example.backend.prediction.CongestionLevel;

/** 인접한 두 정류장 사이의 이동시간과 혼잡도 예측을 나타낸다. */
public record JourneySegmentResponse(
		String fromStopId,
		String fromStopName,
		String toStopId,
		String toStopName,
		int durationMinutes,
		CongestionLevel congestionLevel
) {
}
