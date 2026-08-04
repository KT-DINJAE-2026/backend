package com.example.backend.prediction.dto;

import java.util.List;

import com.example.backend.prediction.StandingBurdenLevel;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 도착 예정 차량 한 대의 여정 정보이다.
 *
 * <p>같은 노선 차량이 연속 도착할 수 있으므로 FE 선택 키는 {@code routeId}가 아니라
 * 차량 단위 {@code tripId}이다. 데이터 부족 응답에서는 혼잡 관련 nullable 필드가 생략된다.</p>
 */
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
