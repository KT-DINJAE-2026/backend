package com.example.backend.stop.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** QR 진입 시 출발 정류장과 초기 목적지 후보를 함께 내려주는 응답이다. */
public record StopContextResponse(
		OffsetDateTime generatedAt,
		CurrentStopResponse currentStop,
		List<DestinationStopResponse> destinationStops
) {
}
