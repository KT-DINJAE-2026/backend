package com.example.backend.prediction.dto;

import com.example.backend.prediction.CongestionLevel;

public record JourneySegmentResponse(
		String fromStopId,
		String fromStopName,
		String toStopId,
		String toStopName,
		int durationMinutes,
		CongestionLevel congestionLevel
) {
}
