package com.example.backend.headway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.example.backend.arrival.ArrivalLookupStatus;
import com.example.backend.arrival.BusArrival;
import com.example.backend.arrival.RouteArrivalSnapshot;
import com.example.backend.arrival.StopArrivalSnapshot;

import org.junit.jupiter.api.Test;

class RealtimeHeadwayResolverTests {

	private static final int STOP_ORDER = 14;
	private final RealtimeHeadwayResolver resolver = new RealtimeHeadwayResolver();

	@Test
	void initialSnapshotKeepsFirstMissingAndUsesEtaDifferenceForSecond() {
		RouteArrivalSnapshot snapshot = snapshot(
				"2026-08-24T17:00:00+09:00",
				arrival("vehicle-a", "route-1", 120),
				arrival("vehicle-b", "route-1", 360)
		);

		assertThat(resolver.headwaySeconds(snapshot, STOP_ORDER)).containsExactly(null, 240L);
	}

	@Test
	void vehiclePromotedFromSecondToFirstUsesItsPreviousVehiclesLastEta() {
		resolver.headwaySeconds(snapshot(
				"2026-08-24T17:00:00+09:00",
				arrival("vehicle-a", "route-1", 60),
				arrival("vehicle-b", "route-1", 300)
		), STOP_ORDER);

		List<Long> headways = resolver.headwaySeconds(snapshot(
				"2026-08-24T17:01:10+09:00",
				arrival("vehicle-b", "route-1", 230),
				arrival("vehicle-c", "route-1", 500)
		), STOP_ORDER);

		assertThat(headways).containsExactly(240L, 270L);
	}

	@Test
	void knownPredecessorIsRetainedWhileTheSameVehicleRemainsFirst() {
		resolver.headwaySeconds(snapshot(
				"2026-08-24T17:00:00+09:00",
				arrival("vehicle-a", "route-1", 60),
				arrival("vehicle-b", "route-1", 300)
		), STOP_ORDER);
		resolver.headwaySeconds(snapshot(
				"2026-08-24T17:01:10+09:00",
				arrival("vehicle-b", "route-1", 230),
				arrival("vehicle-c", "route-1", 500)
		), STOP_ORDER);

		List<Long> headways = resolver.headwaySeconds(snapshot(
				"2026-08-24T17:02:00+09:00",
				arrival("vehicle-b", "route-1", 185),
				arrival("vehicle-c", "route-1", 455)
		), STOP_ORDER);

		assertThat(headways).containsExactly(245L, 270L);
	}

	@Test
	void lateOlderSnapshotDoesNotOverwriteNewerHistory() {
		resolver.headwaySeconds(snapshot(
				"2026-08-24T17:00:00+09:00",
				arrival("vehicle-a", "route-1", 60),
				arrival("vehicle-b", "route-1", 300)
		), STOP_ORDER);
		resolver.headwaySeconds(snapshot(
				"2026-08-24T17:01:10+09:00",
				arrival("vehicle-b", "route-1", 230),
				arrival("vehicle-c", "route-1", 500)
		), STOP_ORDER);

		assertThat(resolver.headwaySeconds(snapshot(
				"2026-08-24T17:00:30+09:00",
				arrival("stale-a", "route-1", 30),
				arrival("stale-b", "route-1", 150)
		), STOP_ORDER)).containsExactly(null, 120L);
		assertThat(resolver.headwaySeconds(snapshot(
				"2026-08-24T17:02:00+09:00",
				arrival("vehicle-b", "route-1", 185),
				arrival("vehicle-c", "route-1", 455)
		), STOP_ORDER)).containsExactly(245L, 270L);
	}

	@Test
	void unknownOrExpiredPredecessorKeepsFirstVehicleMissing() {
		resolver.headwaySeconds(snapshot(
				"2026-08-24T09:00:00+09:00",
				arrival("vehicle-a", "route-1", 60),
				arrival("vehicle-b", "route-1", 300)
		), STOP_ORDER);

		assertThat(resolver.headwaySeconds(snapshot(
				"2026-08-24T09:01:10+09:00",
				arrival("vehicle-x", "route-1", 120),
				arrival("vehicle-y", "route-1", 360)
		), STOP_ORDER)).containsExactly(null, 240L);
		assertThat(resolver.headwaySeconds(snapshot(
				"2026-08-24T15:01:11+09:00",
				arrival("vehicle-y", "route-1", 120),
				arrival("vehicle-z", "route-1", 360)
		), STOP_ORDER)).containsExactly(null, 240L);
	}

	@Test
	void invalidArrivalOrderOrIdentityReturnsMissing() {
		assertThat(resolver.headwaySeconds(snapshot(
				"2026-08-24T17:00:00+09:00",
				arrival("vehicle-a", "route-1", 360),
				arrival("vehicle-b", "route-1", 120)
		), STOP_ORDER)).containsExactly(null, null);
		assertThat(resolver.headwaySeconds(snapshot(
				"2026-08-24T17:01:00+09:00",
				arrival("vehicle-a", "route-1", 120),
				arrival("vehicle-a", "route-1", 360)
		), STOP_ORDER)).containsExactly(null, null);
		assertThat(resolver.headwaySeconds(snapshot(
				"2026-08-24T17:02:00+09:00",
				arrival("vehicle-a", "route-1", 120),
				arrival("vehicle-b", "route-2", 360)
		), STOP_ORDER)).containsExactly(null, null);
		assertThat(resolver.headwaySeconds(snapshot(
				"2026-08-24T17:03:00+09:00",
				arrival("vehicle-a", "route-2", 120),
				arrival("vehicle-b", "route-2", 360)
		), STOP_ORDER)).containsExactly(null, null);
	}

	private static RouteArrivalSnapshot snapshot(String providedAt, BusArrival... arrivals) {
		return new RouteArrivalSnapshot(
				ArrivalLookupStatus.AVAILABLE,
				OffsetDateTime.parse(providedAt),
				"route-1",
				Map.of(STOP_ORDER, new StopArrivalSnapshot(
						"107000087", "성북구청.성북경찰서", STOP_ORDER, List.of(arrivals)
				))
		);
	}

	private static BusArrival arrival(String tripId, String routeId, int seconds) {
		return new BusArrival(
				tripId, routeId, "1014", "종점 방면", "서울74사1001", "일반버스", false,
				seconds, Math.max(0, (seconds + 59) / 60), "", false, false, false
		);
	}
}
