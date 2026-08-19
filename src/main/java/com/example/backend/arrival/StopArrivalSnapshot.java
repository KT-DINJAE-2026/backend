package com.example.backend.arrival;

import java.util.List;

/** 노선의 한 정류장에 대한 첫 번째·두 번째 도착 예정 차량이다. */
public record StopArrivalSnapshot(
		String stopId,
		String stopName,
		int stopOrder,
		List<BusArrival> arrivals
) {

	public StopArrivalSnapshot {
		arrivals = List.copyOf(arrivals);
	}
}
