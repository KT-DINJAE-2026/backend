package com.example.backend.stop.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DestinationStopResponse(
		String stopId,
		String arsId,
		String stopName,
		String directionDescription,
		List<ServedRouteResponse> servedRoutes,
		LocationResponse location
) {
}
