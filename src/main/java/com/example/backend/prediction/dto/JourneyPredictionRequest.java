package com.example.backend.prediction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record JourneyPredictionRequest(
		@NotBlank
		@Pattern(regexp = "\\d{9}", message = "originStopId는 숫자 9자리여야 합니다.")
		String originStopId,
		@NotBlank
		@Pattern(regexp = "\\d{9}", message = "destinationStopId는 숫자 9자리여야 합니다.")
		String destinationStopId
) {
}
