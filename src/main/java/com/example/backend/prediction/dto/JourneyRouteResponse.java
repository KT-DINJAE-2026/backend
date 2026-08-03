package com.example.backend.prediction.dto;

import java.util.List;

import com.example.backend.prediction.StandingBurdenLevel;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JourneyRouteResponse(
		String tripId,
		String routeId,
		String routeNumber,
		String direction,
		String vehicleType,
		boolean isLowFloor,
		int arrivalMinutes,
		int travelMinutes,
		Integer standingBurdenMinutes,
		StandingBurdenLevel standingBurdenLevel,
		List<JourneySegmentResponse> segments
) {
	public JourneyRouteResponse {
		segments = segments == null ? null : List.copyOf(segments);
	}
}
