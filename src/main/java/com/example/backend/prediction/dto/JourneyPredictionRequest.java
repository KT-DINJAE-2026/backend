package com.example.backend.prediction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 출발·도착 정류장 표준 ID로 직통 여정 분석을 요청하는 body이다. */
public record JourneyPredictionRequest(
		@NotBlank
		@Pattern(regexp = "\\d{9}", message = "originStopId는 숫자 9자리여야 합니다.")
		String originStopId,
		@NotBlank
		@Pattern(regexp = "\\d{9}", message = "destinationStopId는 숫자 9자리여야 합니다.")
		String destinationStopId
) {
}
