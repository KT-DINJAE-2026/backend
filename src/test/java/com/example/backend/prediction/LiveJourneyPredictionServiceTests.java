package com.example.backend.prediction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.backend.arrival.ArrivalLookupStatus;
import com.example.backend.arrival.BusArrival;
import com.example.backend.arrival.RouteArrivalProvider;
import com.example.backend.arrival.RouteArrivalSnapshot;
import com.example.backend.arrival.StopArrivalSnapshot;
import com.example.backend.domain.RouteEntity;
import com.example.backend.domain.RouteStopEntity;
import com.example.backend.domain.StopEntity;
import com.example.backend.prediction.dto.JourneyPredictionRequest;
import com.example.backend.prediction.dto.JourneyPredictionResponse;
import com.example.backend.prediction.feature.PredictionModelInputFactory;
import com.example.backend.prediction.model.StandingPrediction;
import com.example.backend.prediction.model.StandingPredictor;
import com.example.backend.repository.RouteRepository;
import com.example.backend.repository.RouteStopRepository;
import com.example.backend.repository.StopRepository;

import org.junit.jupiter.api.Test;

class LiveJourneyPredictionServiceTests {

	@Test
	void combinesTopisArrivalTravelTimeAndPmmlPrediction() {
		Fixture fixture = fixture(input -> StandingPrediction.standing(0.91d, 360.0d));

		JourneyPredictionResponse response = fixture.service().create(
				new JourneyPredictionRequest(fixture.origin().getId(), fixture.destination().getId())
		);

		assertThat(response.status()).isEqualTo(JourneyStatus.SUCCESS);
		assertThat(response.reasonCode()).isNull();
		assertThat(response.predictionBasis().confidence()).isEqualTo(PredictionConfidence.MEDIUM);
		assertThat(response.routes()).hasSize(2);
		assertThat(response.routes().getFirst())
				.satisfies(route -> {
					assertThat(route.tripId()).isEqualTo("vehicle-1");
					assertThat(route.routeId()).isEqualTo("100100129");
					assertThat(route.routeNumber()).isEqualTo("1014");
					assertThat(route.arrivalMinutes()).isEqualTo(2);
					assertThat(route.travelMinutes()).isEqualTo(10);
					assertThat(route.standingBurdenMinutes()).isEqualTo(6);
					assertThat(route.standingBurdenLevel()).isEqualTo(StandingBurdenLevel.HIGH);
					assertThat(route.segments()).singleElement()
							.satisfies(segment -> assertThat(segment.congestionLevel())
									.isEqualTo(CongestionLevel.VERY_CROWDED));
				});
	}

	@Test
	void keepsArrivalAndTravelInformationWhenTheModelCannotPredict() {
		Fixture fixture = fixture(input -> StandingPrediction.unavailable(
				com.example.backend.prediction.model.StandingPredictionStatus.OUT_OF_DOMAIN
		));

		JourneyPredictionResponse response = fixture.service().create(
				new JourneyPredictionRequest(fixture.origin().getId(), fixture.destination().getId())
		);

		assertThat(response.status()).isEqualTo(JourneyStatus.INSUFFICIENT_DATA);
		assertThat(response.reasonCode()).isEqualTo("NOT_ENOUGH_HISTORICAL_SAMPLES");
		assertThat(response.routes()).allSatisfy(route -> {
			assertThat(route.arrivalMinutes()).isPositive();
			assertThat(route.travelMinutes()).isEqualTo(10);
			assertThat(route.standingBurdenMinutes()).isNull();
			assertThat(route.standingBurdenLevel()).isNull();
			assertThat(route.segments()).isNull();
		});
	}

	@Test
	void mixedPredictionAvailabilityKeepsAllVehiclesAsInsufficientData() {
		AtomicInteger calls = new AtomicInteger();
		Fixture fixture = fixture(input -> calls.getAndIncrement() == 0
				? StandingPrediction.seated(0.2d)
				: StandingPrediction.unavailable(
						com.example.backend.prediction.model.StandingPredictionStatus.OUT_OF_DOMAIN
				));

		JourneyPredictionResponse response = fixture.service().create(
				new JourneyPredictionRequest(fixture.origin().getId(), fixture.destination().getId())
		);

		assertThat(response.status()).isEqualTo(JourneyStatus.INSUFFICIENT_DATA);
		assertThat(response.routes()).hasSize(2).allSatisfy(route -> {
			assertThat(route.arrivalMinutes()).isPositive();
			assertThat(route.standingBurdenLevel()).isNull();
			assertThat(route.segments()).isNull();
		});
	}

	@Test
	void returnsUpToTwoVehiclesForEveryDirectRouteWithoutAGlobalLimit() {
		StopRepository stopRepository = mock(StopRepository.class);
		RouteRepository routeRepository = mock(RouteRepository.class);
		RouteStopRepository routeStopRepository = mock(RouteStopRepository.class);
		StopEntity origin = stop("107000087", "성북구청.성북경찰서");
		StopEntity destination = stop("107000089", "보문역");
		List<RouteEntity> routes = List.of(
				new RouteEntity("100100129", "11100", "1", "1014", "B", "기점", "종점", "115"),
				new RouteEntity("100100031", "11100", "2", "103", "B", "기점", "종점", "115"),
				new RouteEntity("100100008", "11100", "3", "142", "B", "기점", "종점", "115")
		);
		Map<String, RouteArrivalSnapshot> snapshots = new java.util.HashMap<>();

		when(stopRepository.getRequired(origin.getId())).thenReturn(origin);
		when(stopRepository.getRequired(destination.getId())).thenReturn(destination);
		when(routeRepository.findDirectRoutes(origin.getId(), destination.getId())).thenReturn(routes);
		for (RouteEntity route : routes) {
			RouteStopEntity originRouteStop = new RouteStopEntity(route, origin, 14, 522);
			RouteStopEntity destinationRouteStop = new RouteStopEntity(route, destination, 15, 302);
			when(routeStopRepository.findByRoute_IdAndStop_IdOrderByStopOrder(route.getId(), origin.getId()))
					.thenReturn(List.of(originRouteStop));
			when(routeStopRepository.findByRoute_IdAndStop_IdOrderByStopOrder(route.getId(), destination.getId()))
					.thenReturn(List.of(destinationRouteStop));
			when(routeStopRepository.findByRoute_IdAndStopOrderBetweenOrderByStopOrder(route.getId(), 14, 15))
					.thenReturn(List.of(originRouteStop, destinationRouteStop));
			snapshots.put(route.getId(), snapshot(route, originRouteStop, destinationRouteStop));
		}

		PredictionModelInputFactory inputFactory = new PredictionModelInputFactory(
				date -> false,
				(latitude, longitude, boardingTime) -> "맑음",
				(routeNumber, serviceDate, publicHoliday) -> 480L
		);
		Clock clock = Clock.fixed(Instant.parse("2026-08-19T05:30:00Z"), ZoneId.of("Asia/Seoul"));
		LiveJourneyPredictionService service = new LiveJourneyPredictionService(
				stopRepository, routeRepository, routeStopRepository, snapshots::get, inputFactory,
				input -> StandingPrediction.seated(0.2d), new JourneyTravelTimeEstimator(), clock
		);

		JourneyPredictionResponse response = service.create(
				new JourneyPredictionRequest(origin.getId(), destination.getId())
		);

		assertThat(response.routes()).hasSize(6);
		assertThat(response.routes()).extracting(route -> route.routeId())
				.containsOnly("100100129", "100100031", "100100008");
		for (RouteEntity route : routes) {
			assertThat(response.routes()).filteredOn(item -> item.routeId().equals(route.getId())).hasSize(2);
		}
	}

	private static Fixture fixture(StandingPredictor predictor) {
		StopRepository stopRepository = mock(StopRepository.class);
		RouteRepository routeRepository = mock(RouteRepository.class);
		RouteStopRepository routeStopRepository = mock(RouteStopRepository.class);
		StopEntity origin = stop("107000087", "성북구청.성북경찰서");
		StopEntity destination = stop("107000089", "보문역");
		RouteEntity route = new RouteEntity(
				"100100129", "11100", "11110113", "1014", "B", "성북생태체험관", "동묘앞", "115"
		);
		RouteStopEntity originRouteStop = new RouteStopEntity(route, origin, 14, 522);
		RouteStopEntity destinationRouteStop = new RouteStopEntity(route, destination, 15, 302);

		when(stopRepository.getRequired(origin.getId())).thenReturn(origin);
		when(stopRepository.getRequired(destination.getId())).thenReturn(destination);
		when(routeRepository.findDirectRoutes(origin.getId(), destination.getId())).thenReturn(List.of(route));
		when(routeStopRepository.findByRoute_IdAndStop_IdOrderByStopOrder(route.getId(), origin.getId()))
				.thenReturn(List.of(originRouteStop));
		when(routeStopRepository.findByRoute_IdAndStop_IdOrderByStopOrder(route.getId(), destination.getId()))
				.thenReturn(List.of(destinationRouteStop));
		when(routeStopRepository.findByRoute_IdAndStopOrderBetweenOrderByStopOrder(route.getId(), 14, 15))
				.thenReturn(List.of(originRouteStop, destinationRouteStop));

		RouteArrivalProvider arrivalProvider = ignored -> snapshot(originRouteStop, destinationRouteStop);
		PredictionModelInputFactory inputFactory = new PredictionModelInputFactory(
				date -> false,
				(latitude, longitude, boardingTime) -> "맑음",
				(routeNumber, serviceDate, publicHoliday) -> 480L
		);
		Clock clock = Clock.fixed(Instant.parse("2026-08-19T05:30:00Z"), ZoneId.of("Asia/Seoul"));
		LiveJourneyPredictionService service = new LiveJourneyPredictionService(
				stopRepository,
				routeRepository,
				routeStopRepository,
				arrivalProvider,
				inputFactory,
				predictor,
				new JourneyTravelTimeEstimator(),
				clock
		);
		return new Fixture(service, origin, destination);
	}

	private static RouteArrivalSnapshot snapshot(
			RouteStopEntity origin,
			RouteStopEntity destination
	) {
		BusArrival firstAtOrigin = arrival("vehicle-1", 120, true);
		BusArrival secondAtOrigin = arrival("vehicle-2", 360, false);
		BusArrival firstAtDestination = arrival("vehicle-1", 720, true);
		BusArrival secondAtDestination = arrival("vehicle-2", 960, false);
		return new RouteArrivalSnapshot(
				ArrivalLookupStatus.AVAILABLE,
				OffsetDateTime.parse("2026-08-19T14:30:00+09:00"),
				"100100129",
				Map.of(
						14, new StopArrivalSnapshot(
								origin.getStop().getId(), origin.getStop().getName(), 14,
								List.of(firstAtOrigin, secondAtOrigin)
						),
						15, new StopArrivalSnapshot(
								destination.getStop().getId(), destination.getStop().getName(), 15,
								List.of(firstAtDestination, secondAtDestination)
						)
				)
		);
	}

	private static RouteArrivalSnapshot snapshot(
			RouteEntity route,
			RouteStopEntity origin,
			RouteStopEntity destination
	) {
		BusArrival firstAtOrigin = arrival(route, route.getId() + "-vehicle-1", 120, true);
		BusArrival secondAtOrigin = arrival(route, route.getId() + "-vehicle-2", 360, false);
		BusArrival firstAtDestination = arrival(route, route.getId() + "-vehicle-1", 720, true);
		BusArrival secondAtDestination = arrival(route, route.getId() + "-vehicle-2", 960, false);
		return new RouteArrivalSnapshot(
				ArrivalLookupStatus.AVAILABLE,
				OffsetDateTime.parse("2026-08-19T14:30:00+09:00"),
				route.getId(),
				Map.of(
						14, new StopArrivalSnapshot(
								origin.getStop().getId(), origin.getStop().getName(), 14,
								List.of(firstAtOrigin, secondAtOrigin)
						),
						15, new StopArrivalSnapshot(
								destination.getStop().getId(), destination.getStop().getName(), 15,
								List.of(firstAtDestination, secondAtDestination)
						)
				)
		);
	}

	private static BusArrival arrival(String tripId, int seconds, boolean lowFloor) {
		return new BusArrival(
				tripId,
				"100100129",
				"1014",
				"동묘앞 방면",
				"서울74사1001",
				lowFloor ? "저상버스" : "일반버스",
				lowFloor,
				seconds,
				(seconds + 59) / 60,
				"",
				false,
				false,
				false
		);
	}

	private static BusArrival arrival(
			RouteEntity route,
			String tripId,
			int seconds,
			boolean lowFloor
	) {
		return new BusArrival(
				tripId, route.getId(), route.getNumber(), "종점 방면", "서울74사1001",
				lowFloor ? "저상버스" : "일반버스", lowFloor,
				seconds, (seconds + 59) / 60, "", false, false, false
		);
	}

	private static StopEntity stop(String id, String name) {
		return new StopEntity(
				id, "11100", "local-" + id, null, name, "성북구",
				new BigDecimal("37.58815000"), new BigDecimal("127.01743000")
		);
	}

	private record Fixture(
			LiveJourneyPredictionService service,
			StopEntity origin,
			StopEntity destination
	) {
	}
}
