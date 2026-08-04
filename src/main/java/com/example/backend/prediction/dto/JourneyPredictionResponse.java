package com.example.backend.prediction.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.example.backend.prediction.JourneyStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 직통 여정 분석의 최상위 응답이다.
 * 데이터 부족 시 {@code reasonCode}를 제공하고, 계산할 수 없는 하위 혼잡 필드는 생략한다.
 */
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
		// 응답 생성 뒤 노선 목록이 변경되어 계약 검증 결과가 달라지는 것을 막는다.
		routes = List.copyOf(routes);
	}
}
