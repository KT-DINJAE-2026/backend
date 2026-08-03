package com.example.backend.stop.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record StopContextResponse(
		OffsetDateTime generatedAt,
		CurrentStopResponse currentStop,
		List<DestinationStopResponse> destinationStops
) {
}
