package com.example.backend.stop.dto;

import java.util.List;

public record StopSearchResponse(
		List<DestinationStopResponse> destinationStops
) {
}
