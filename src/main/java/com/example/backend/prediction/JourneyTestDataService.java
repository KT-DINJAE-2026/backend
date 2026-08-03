package com.example.backend.prediction;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.backend.domain.RouteEntity;
import com.example.backend.domain.RouteStopEntity;
import com.example.backend.domain.StopEntity;
import com.example.backend.error.ApiException;
import com.example.backend.error.ErrorCode;
import com.example.backend.prediction.dto.JourneyPredictionRequest;
import com.example.backend.prediction.dto.JourneyPredictionResponse;
import com.example.backend.prediction.dto.JourneyRouteResponse;
import com.example.backend.prediction.dto.JourneySegmentResponse;
import com.example.backend.prediction.dto.PredictionBasisResponse;
import com.example.backend.repository.RouteRepository;
import com.example.backend.repository.RouteStopRepository;
import com.example.backend.repository.StopRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 연동 방식이 확정되기 전까지 FE 계약 검증용 데이터를 반환합니다.
 */
@Service
@Transactional(readOnly = true)
public class JourneyTestDataService {

	private static final String INSUFFICIENT_REASON = "NOT_ENOUGH_HISTORICAL_SAMPLES";
	private static final String INSUFFICIENT_DESTINATION_STOP_ID = "100000147";

	private static final Map<String, TestRoute> SUCCESS_ROUTES = Map.of(
			"100100129", new TestRoute("mock-trip-100100129-1405", 5, 3, true, 0, StandingBurdenLevel.LOW),
			"100100031", new TestRoute("mock-trip-100100031-1402", 2, 3, true, 3, StandingBurdenLevel.HIGH),
			"100100008", new TestRoute("mock-trip-100100008-1404", 4, 3, false, 3, StandingBurdenLevel.MEDIUM),
			"100100021", new TestRoute("mock-trip-100100021-1407", 7, 3, true, 0, StandingBurdenLevel.LOW)
	);

	private static final Map<String, TestRoute> INSUFFICIENT_ROUTES = Map.of(
			"100100129", new TestRoute("mock-trip-100100129-1404", 4, 10, true, null, null),
			"100100031", new TestRoute("mock-trip-100100031-1407", 7, 10, true, null, null),
			"100100008", new TestRoute("mock-trip-100100008-1409", 9, 11, false, null, null)
	);

	private final StopRepository stopRepository;
	private final RouteRepository routeRepository;
	private final RouteStopRepository routeStopRepository;
	private final Clock clock;

	public JourneyTestDataService(
			StopRepository stopRepository,
			RouteRepository routeRepository,
			RouteStopRepository routeStopRepository,
			Clock clock
	) {
		this.stopRepository = stopRepository;
		this.routeRepository = routeRepository;
		this.routeStopRepository = routeStopRepository;
		this.clock = clock;
	}

	public JourneyPredictionResponse create(JourneyPredictionRequest request) {
		StopEntity origin = getStop(request.originStopId());
		StopEntity destination = getStop(request.destinationStopId());
		List<RouteEntity> directRoutes = routeRepository.findDirectRoutes(origin.getId(), destination.getId());
		if (directRoutes.isEmpty()) {
			if (!routeRepository.findRoutesConnectingStops(origin.getId(), destination.getId()).isEmpty()) {
				throw new ApiException(ErrorCode.STOP_DIRECTION_MISMATCH);
			}
			throw new ApiException(ErrorCode.NO_DIRECT_ROUTE);
		}

		boolean insufficient = INSUFFICIENT_DESTINATION_STOP_ID.equals(destination.getId());
		List<JourneyRouteResponse> routes = new ArrayList<>();
		for (int index = 0; index < directRoutes.size(); index++) {
			RouteEntity route = directRoutes.get(index);
			Optional<List<RouteStopEntity>> path = findPath(route.getId(), origin.getId(), destination.getId());
			if (path.isEmpty()) {
				continue;
			}
			TestRoute testRoute = testRoute(route, path.get(), index, insufficient);
			routes.add(toResponse(route, path.get(), testRoute, insufficient));
		}

		return new JourneyPredictionResponse(
				insufficient ? JourneyStatus.INSUFFICIENT_DATA : JourneyStatus.SUCCESS,
				insufficient ? INSUFFICIENT_REASON : null,
				OffsetDateTime.now(clock),
				origin.getId(),
				destination.getId(),
				new PredictionBasisResponse(
						insufficient ? PredictionConfidence.UNAVAILABLE : PredictionConfidence.MEDIUM
				),
				routes
		);
	}

	private TestRoute testRoute(
			RouteEntity route,
			List<RouteStopEntity> path,
			int index,
			boolean insufficient
	) {
		Map<String, TestRoute> configured = insufficient ? INSUFFICIENT_ROUTES : SUCCESS_ROUTES;
		TestRoute predefined = configured.get(route.getId());
		if (predefined != null) {
			return predefined;
		}
		int segmentCount = Math.max(1, path.size() - 1);
		int travelMinutes = Math.max(3, segmentCount * 2);
		boolean lowFloor = index % 2 == 0;
		if (insufficient) {
			return new TestRoute(
					"test-" + route.getId() + "-vehicle", 3 + index * 3, travelMinutes,
					lowFloor, null, null
			);
		}
		StandingBurdenLevel level = switch (index % 3) {
			case 0 -> StandingBurdenLevel.MEDIUM;
			case 1 -> StandingBurdenLevel.LOW;
			default -> StandingBurdenLevel.HIGH;
		};
		int standingMinutes = level == StandingBurdenLevel.LOW ? 0 : Math.min(4, travelMinutes);
		return new TestRoute(
				"test-" + route.getId() + "-vehicle", 3 + index * 3, travelMinutes,
				lowFloor, standingMinutes, level
		);
	}

	private JourneyRouteResponse toResponse(
			RouteEntity route,
			List<RouteStopEntity> path,
			TestRoute testRoute,
			boolean insufficient
	) {
		String vehicleType = testRoute.lowFloor() ? "저상버스" : "일반버스";
		if (insufficient) {
			return new JourneyRouteResponse(
					testRoute.tripId(), route.getId(), route.getNumber(), direction(route),
					vehicleType, testRoute.lowFloor(), testRoute.arrivalMinutes(), testRoute.travelMinutes(),
					null, null, null
			);
		}
		return new JourneyRouteResponse(
				testRoute.tripId(), route.getId(), route.getNumber(), direction(route),
				vehicleType, testRoute.lowFloor(), testRoute.arrivalMinutes(), testRoute.travelMinutes(),
				testRoute.standingBurdenMinutes(), testRoute.standingBurdenLevel(),
				segments(path, testRoute)
		);
	}

	private List<JourneySegmentResponse> segments(List<RouteStopEntity> path, TestRoute testRoute) {
		int segmentCount = path.size() - 1;
		if (segmentCount <= 0) {
			return List.of();
		}
		int baseDuration = testRoute.travelMinutes() / segmentCount;
		int remainder = testRoute.travelMinutes() % segmentCount;
		int elapsedMinutes = 0;
		List<JourneySegmentResponse> segments = new ArrayList<>(segmentCount);
		for (int index = 0; index < segmentCount; index++) {
			RouteStopEntity from = path.get(index);
			RouteStopEntity to = path.get(index + 1);
			int duration = baseDuration + (index < remainder ? 1 : 0);
			boolean standing = elapsedMinutes < testRoute.standingBurdenMinutes();
			segments.add(new JourneySegmentResponse(
					from.getStop().getId(), from.getStop().getName(),
					to.getStop().getId(), to.getStop().getName(),
					duration,
					standing ? congestionLevel(testRoute.standingBurdenLevel()) : CongestionLevel.RELAXED
			));
			elapsedMinutes += duration;
		}
		return segments;
	}

	private Optional<List<RouteStopEntity>> findPath(
			String routeId,
			String originStopId,
			String destinationStopId
	) {
		List<RouteStopEntity> origins = routeStopRepository
				.findByRoute_IdAndStop_IdOrderByStopOrder(routeId, originStopId);
		List<RouteStopEntity> destinations = routeStopRepository
				.findByRoute_IdAndStop_IdOrderByStopOrder(routeId, destinationStopId);
		int[] selected = origins.stream()
				.flatMap(origin -> destinations.stream()
						.filter(destination -> destination.getStopOrder() > origin.getStopOrder())
						.map(destination -> new int[] {origin.getStopOrder(), destination.getStopOrder()}))
				.min(Comparator.comparingInt(pair -> pair[1] - pair[0]))
				.orElse(null);
		if (selected == null) {
			return Optional.empty();
		}
		return Optional.of(routeStopRepository.findByRoute_IdAndStopOrderBetweenOrderByStopOrder(
				routeId, selected[0], selected[1]
		));
	}

	private StopEntity getStop(String stopId) {
		return stopRepository.findById(stopId)
				.orElseThrow(() -> new ApiException(ErrorCode.STOP_NOT_FOUND));
	}

	private static String direction(RouteEntity route) {
		return route.getEndStopName() == null ? null : route.getEndStopName() + " 방면";
	}

	private static CongestionLevel congestionLevel(StandingBurdenLevel level) {
		return switch (level) {
			case LOW -> CongestionLevel.NORMAL;
			case MEDIUM -> CongestionLevel.CROWDED;
			case HIGH -> CongestionLevel.VERY_CROWDED;
		};
	}

	private record TestRoute(
			String tripId,
			int arrivalMinutes,
			int travelMinutes,
			boolean lowFloor,
			Integer standingBurdenMinutes,
			StandingBurdenLevel standingBurdenLevel
	) {
	}
}
