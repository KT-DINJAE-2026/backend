package com.example.backend.prediction;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.backend.domain.BusVehicleType;
import com.example.backend.domain.RouteDirectionDescription;
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

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 연동 방식이 확정되기 전까지 FE 계약 검증용 여정 데이터를 만든다.
 *
 * <p>정류장과 직통 노선 및 경유 순서는 DB의 실제 기반정보를 사용하지만,
 * 도착시간·차량·혼잡도 값은 고정 시나리오 또는 결정적인 규칙으로 생성한다.
 * TOPIS나 실제 AI 결과로 오해하지 않도록 운영 서비스로 그대로 사용하면 안 된다.</p>
 */
@Service
@ConditionalOnProperty(prefix = "app.demo", name = "enabled", havingValue = "true")
@Transactional(readOnly = true)
public class JourneyTestDataService implements JourneyPredictionService {

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

	@Override
	public JourneyPredictionResponse create(JourneyPredictionRequest request) {
		StopEntity origin = stopRepository.getRequired(request.originStopId());
		StopEntity destination = stopRepository.getRequired(request.destinationStopId());
		List<RouteEntity> directRoutes = routeRepository.findDirectRoutes(origin.getId(), destination.getId());
		if (directRoutes.isEmpty()) {
			// 두 정류장을 모두 지나지만 순서가 반대인 경우와 아예 공통 노선이 없는 경우를 구분한다.
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
		// FE Mock과 합의된 데모 노선은 고정값을 유지해 화면 회귀 테스트가 흔들리지 않게 한다.
		TestRoute predefined = configured.get(route.getId());
		if (predefined != null) {
			return predefined;
		}
		// 다른 직통 구간도 API 구조를 시험할 수 있도록 노선 순서 기반의 결정적인 값을 만든다.
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
		String vehicleType = (testRoute.lowFloor() ? BusVehicleType.LOW_FLOOR : BusVehicleType.STANDARD)
				.displayName();
		if (insufficient) {
			// 데이터 부족은 200 응답을 유지하되 근거 없는 혼잡 관련 필드는 null로 두어 JSON에서 생략한다.
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
		// 전체 구간 합이 travelMinutes와 정확히 같도록 몫과 나머지를 앞 구간부터 배분한다.
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
		/*
		 * 순환 노선은 같은 정류장 ID가 여러 순번에 존재할 수 있다.
		 * 가능한 정방향 순번 쌍 중 가장 짧은 구간을 선택해 한 바퀴를 불필요하게 도는 경로를 피한다.
		 */
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

	private static String direction(RouteEntity route) {
		return RouteDirectionDescription.fromEndStopName(route.getEndStopName());
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
