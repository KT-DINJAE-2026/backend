package com.example.backend.arrival;

import java.time.OffsetDateTime;
import java.util.List;

public record ArrivalLookupResult(
		ArrivalLookupStatus status,
		OffsetDateTime providedAt,
		List<BusArrival> arrivals
) {

	public ArrivalLookupResult {
		arrivals = List.copyOf(arrivals);
	}

	public static ArrivalLookupResult empty(ArrivalLookupStatus status, OffsetDateTime providedAt) {
		return new ArrivalLookupResult(status, providedAt, List.of());
	}
}
