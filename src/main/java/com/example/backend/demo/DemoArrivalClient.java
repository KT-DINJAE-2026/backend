package com.example.backend.demo;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import com.example.backend.arrival.ArrivalClient;
import com.example.backend.arrival.ArrivalLookupResult;
import com.example.backend.arrival.ArrivalLookupStatus;
import com.example.backend.arrival.BusArrival;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.demo", name = "enabled", havingValue = "true")
public class DemoArrivalClient implements ArrivalClient {

	private final Clock clock;

	public DemoArrivalClient(Clock clock) {
		this.clock = clock;
	}

	@Override
	public ArrivalLookupResult getArrivals(String stopId, String routeId, int stopOrder) {
		List<BusArrival> arrivals = switch (routeId) {
			case "100100027" -> List.of(
					arrival(routeId, "148", "demo-148-low", 2, true),
					arrival(routeId, "148", "demo-148-normal", 7, false)
			);
			case "100100057" -> List.of(
					arrival(routeId, "360", "demo-360-low", 4, true)
			);
			case "113000002" -> List.of(
					arrival(routeId, "452", "demo-452-low", 5, true)
			);
			default -> List.of();
		};
		ArrivalLookupStatus status = arrivals.isEmpty()
				? ArrivalLookupStatus.NO_ARRIVAL
				: ArrivalLookupStatus.AVAILABLE;
		return new ArrivalLookupResult(status, OffsetDateTime.now(clock), arrivals);
	}

	private static BusArrival arrival(
			String routeId,
			String routeNumber,
			String tripId,
			int arrivalMinutes,
			boolean lowFloor
	) {
		return new BusArrival(
				tripId,
				routeId,
				routeNumber,
				"서초구 방면",
				null,
				lowFloor ? "저상버스" : "일반버스",
				lowFloor,
				arrivalMinutes * 60,
				arrivalMinutes,
				arrivalMinutes + "분 후 도착",
				false,
				false,
				false
		);
	}
}
