package com.example.backend.stop.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** QR의 {@code stopId}로 확인한 현재 출발 정류장 정보이다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CurrentStopResponse(
		String stopId,
		String arsId,
		String stopName,
		String directionDescription,
		LocationResponse location
) {
}
