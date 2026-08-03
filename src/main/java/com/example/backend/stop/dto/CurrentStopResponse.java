package com.example.backend.stop.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CurrentStopResponse(
		String stopId,
		String arsId,
		String stopName,
		String directionDescription,
		LocationResponse location
) {
}
