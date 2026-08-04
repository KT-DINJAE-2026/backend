package com.example.backend.stop.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 검색 또는 초기 추천에 표시할 도착 정류장 정보이다.
 * {@code servedRoutes}가 빈 배열이면 정류장은 존재하지만 출발지에서 직통으로 갈 수 없다는 뜻이다.
 */
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
