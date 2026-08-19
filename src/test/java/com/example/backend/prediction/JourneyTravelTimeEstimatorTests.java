package com.example.backend.prediction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.example.backend.arrival.ArrivalLookupStatus;
import com.example.backend.arrival.BusArrival;
import com.example.backend.arrival.RouteArrivalSnapshot;
import com.example.backend.arrival.StopArrivalSnapshot;
import com.example.backend.domain.RouteEntity;
import com.example.backend.domain.RouteStopEntity;
import com.example.backend.domain.StopEntity;

import org.junit.jupiter.api.Test;

class JourneyTravelTimeEstimatorTests {

	private final JourneyTravelTimeEstimator estimator = new JourneyTravelTimeEstimator();

	@Test
	void usesTheSameVehiclesEtaDifferenceForEachSegment() {
		RouteEntity route = route();
		RouteStopEntity origin = routeStop(route, stop("107000087", "성북구청"), 14, 522);
		RouteStopEntity destination = routeStop(route, stop("107000089", "보문역"), 15, 302);
		RouteArrivalSnapshot snapshot = new RouteArrivalSnapshot(
				ArrivalLookupStatus.AVAILABLE,
				OffsetDateTime.parse("2026-08-19T14:31:35+09:00"),
				route.getId(),
				Map.of(
						14, stopSnapshot(origin, arrival("vehicle-1", 114), arrival("vehicle-2", 322)),
						15, stopSnapshot(destination, arrival("vehicle-1", 210), arrival("vehicle-2", 418))
				)
		);

		JourneyTravelTimeEstimator.TravelEstimate estimate = estimator.estimate(
				List.of(origin, destination), snapshot
		);

		assertThat(estimate.totalSeconds()).isEqualTo(96);
		assertThat(estimate.totalMinutes()).isEqualTo(2);
		assertThat(estimate.segments()).singleElement()
				.satisfies(segment -> {
					assertThat(segment.durationSeconds()).isEqualTo(96);
					assertThat(segment.durationMinutes()).isEqualTo(2);
				});
	}

	@Test
	void fallsBackToSectionDistanceWhenNoVehicleAppearsAtBothStops() {
		RouteEntity route = route();
		RouteStopEntity origin = routeStop(route, stop("107000087", "성북구청"), 14, 522);
		RouteStopEntity destination = routeStop(route, stop("107000089", "보문역"), 15, 300);
		RouteArrivalSnapshot snapshot = new RouteArrivalSnapshot(
				ArrivalLookupStatus.AVAILABLE,
				OffsetDateTime.parse("2026-08-19T14:31:35+09:00"),
				route.getId(),
				Map.of(
						14, stopSnapshot(origin, arrival("vehicle-1", 120)),
						15, stopSnapshot(destination, arrival("vehicle-2", 240))
				)
		);

		JourneyTravelTimeEstimator.TravelEstimate estimate = estimator.estimate(
				List.of(origin, destination), snapshot
		);

		assertThat(estimate.totalSeconds()).isEqualTo(72);
		assertThat(estimate.totalMinutes()).isEqualTo(2);
	}

	@Test
	void usesAConservativeDefaultWhenEtaAndDistanceAreBothMissing() {
		RouteEntity route = route();
		RouteStopEntity origin = routeStop(route, stop("107000087", "성북구청"), 14, null);
		RouteStopEntity destination = routeStop(route, stop("107000089", "보문역"), 15, null);
		RouteArrivalSnapshot snapshot = new RouteArrivalSnapshot(
				ArrivalLookupStatus.AVAILABLE,
				OffsetDateTime.parse("2026-08-19T14:31:35+09:00"),
				route.getId(),
				Map.of()
		);

		JourneyTravelTimeEstimator.TravelEstimate estimate = estimator.estimate(
				List.of(origin, destination), snapshot
		);

		assertThat(estimate.totalSeconds()).isEqualTo(120);
		assertThat(estimate.totalMinutes()).isEqualTo(2);
	}

	private static StopArrivalSnapshot stopSnapshot(RouteStopEntity stop, BusArrival... arrivals) {
		return new StopArrivalSnapshot(
				stop.getStop().getId(), stop.getStop().getName(), stop.getStopOrder(), List.of(arrivals)
		);
	}

	private static BusArrival arrival(String tripId, int seconds) {
		return new BusArrival(
				tripId, "100100129", "1014", "동묘앞 방면", "서울74사1001", "저상버스",
				true, seconds, (seconds + 59) / 60, "", false, false, false
		);
	}

	private static RouteStopEntity routeStop(
			RouteEntity route,
			StopEntity stop,
			int order,
			Integer distance
	) {
		return new RouteStopEntity(route, stop, order, distance);
	}

	private static RouteEntity route() {
		return new RouteEntity(
				"100100129", "11100", "11110113", "1014", "B", "성북생태체험관", "동묘앞", "115"
		);
	}

	private static StopEntity stop(String id, String name) {
		return new StopEntity(
				id, "11100", "local-" + id, null, name, "성북구",
				new BigDecimal("37.58815000"), new BigDecimal("127.01743000")
		);
	}
}
