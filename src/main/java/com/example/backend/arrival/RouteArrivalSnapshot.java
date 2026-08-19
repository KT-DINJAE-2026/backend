package com.example.backend.arrival;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** TOPIS가 제공한 노선 전체 정류장의 실시간 도착예정 스냅샷이다. */
public record RouteArrivalSnapshot(
		ArrivalLookupStatus status,
		OffsetDateTime providedAt,
		String routeId,
		Map<Integer, StopArrivalSnapshot> stopsByOrder
) {

	public RouteArrivalSnapshot {
		stopsByOrder = Map.copyOf(new LinkedHashMap<>(stopsByOrder));
	}

	public static RouteArrivalSnapshot empty(
			ArrivalLookupStatus status,
			OffsetDateTime providedAt,
			String routeId
	) {
		return new RouteArrivalSnapshot(status, providedAt, routeId, Map.of());
	}

	public List<BusArrival> arrivalsAt(int stopOrder) {
		StopArrivalSnapshot stop = stopsByOrder.get(stopOrder);
		return stop == null ? List.of() : stop.arrivals();
	}
}
