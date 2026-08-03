package com.example.backend.prediction.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.example.backend.prediction.JourneyStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JourneyPredictionResponse(
		JourneyStatus status,
		String reasonCode,
		OffsetDateTime generatedAt,
		String originStopId,
		String destinationStopId,
		PredictionBasisResponse predictionBasis,
		List<JourneyRouteResponse> routes
) {
	public JourneyPredictionResponse {
		routes = List.copyOf(routes);
	}
}
