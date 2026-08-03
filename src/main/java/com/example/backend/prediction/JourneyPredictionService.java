package com.example.backend.prediction;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.example.backend.arrival.ArrivalLookupResult;
import com.example.backend.arrival.BusArrival;
import com.example.backend.arrival.TopisApiException;
import com.example.backend.arrival.TopisArrivalService;
import com.example.backend.config.AppProperties;
import com.example.backend.domain.PredictionEntity;
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
import com.example.backend.repository.PredictionRepository;
import com.example.backend.repository.RouteRepository;
import com.example.backend.repository.RouteStopRepository;
import com.example.backend.repository.StopRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class JourneyPredictionService {

	private static final Logger log = LoggerFactory.getLogger(JourneyPredictionService.class);
	private static final String INSUFFICIENT_REASON = "NOT_ENOUGH_HISTORICAL_SAMPLES";
	private static final Set<String> SELECTABLE_USER_TYPES = Set.of("01", "02", "04", "05");

	private final StopRepository stopRepository;
	private final RouteRepository routeRepository;
	private final RouteStopRepository routeStopRepository;
	private final PredictionRepository predictionRepository;
	private final TopisArrivalService arrivalService;
	private final AppProperties.Prediction properties;
	private final Clock clock;

	public JourneyPredictionService(
			StopRepository stopRepository,
			RouteRepository routeRepository,
			RouteStopRepository routeStopRepository,
			PredictionRepository predictionRepository,
			TopisArrivalService arrivalService,
			AppProperties appProperties,
			Clock clock
	) {
		this.stopRepository = stopRepository;
		this.routeRepository = routeRepository;
		this.routeStopRepository = routeStopRepository;
		this.predictionRepository = predictionRepository;
		this.arrivalService = arrivalService;
		this.properties = appProperties.getPrediction();
		this.clock = clock;
	}

	public JourneyPredictionResponse predict(JourneyPredictionRequest request) {
		StopEntity origin = getStop(request.originStopId());
		StopEntity destination = getStop(request.destinationStopId());
		List<RouteEntity> directRoutes = routeRepository.findDirectRoutes(origin.getId(), destination.getId());
		if (directRoutes.isEmpty()) {
			if (!routeRepository.findRoutesConnectingStops(origin.getId(), destination.getId()).isEmpty()) {
				throw new ApiException(ErrorCode.STOP_DIRECTION_MISMATCH);
			}
			throw new ApiException(ErrorCode.NO_DIRECT_ROUTE);
		}

		OffsetDateTime generatedAt = OffsetDateTime.now(clock);
		String weekday = koreanWeekday(generatedAt.getDayOfWeek());
		String weather = properties.getDefaultWeather();
		String userTypeCode = resolveUserType(request.usertypeCode());
		List<RoutePlan> plans = directRoutes.stream()
				.map(route -> createPlan(route, origin, destination, weekday, generatedAt.getHour(), weather, userTypeCode))
				.flatMap(Optional::stream)
				.toList();

		boolean sufficient = plans.size() == directRoutes.size()
				&& plans.stream().allMatch(this::isSufficient);
		int minimumSampleCount = plans.size() == directRoutes.size()
				? plans.stream().mapToInt(this::sampleCount).min().orElse(0)
				: 0;
		PredictionConfidence confidence = confidence(minimumSampleCount);
		JourneyStatus status = sufficient ? JourneyStatus.SUCCESS : JourneyStatus.INSUFFICIENT_DATA;

		List<JourneyRouteResponse> routes = new ArrayList<>();
		Set<String> tripIds = new HashSet<>();
		for (RoutePlan plan : plans) {
			if (plan.prediction().getTravelSeconds() <= 0) {
				continue;
			}
			for (BusArrival arrival : arrivals(plan)) {
				String tripId = uniqueTripId(arrival.tripId(), plan.route().getId(), tripIds);
				routes.add(toResponse(plan, arrival, tripId, sufficient));
			}
		}

		return new JourneyPredictionResponse(
				status,
				sufficient ? null : INSUFFICIENT_REASON,
				generatedAt,
				origin.getId(),
				destination.getId(),
				new PredictionBasisResponse(confidence),
				routes
		);
	}

	private Optional<RoutePlan> createPlan(
			RouteEntity route,
			StopEntity origin,
			StopEntity destination,
			String weekday,
			int hour,
			String weather,
			String userTypeCode
	) {
		Optional<List<RouteStopEntity>> path = findPath(route.getId(), origin.getId(), destination.getId());
		if (path.isEmpty()) {
			return Optional.empty();
		}
		Optional<PredictionEntity> prediction = predictionRepository
				.findFirstByRoute_IdAndBoardingStop_IdAndAlightingStop_IdAndWeekdayAndHourAndWeatherAndUserTypeCode(
						route.getId(), origin.getId(), destination.getId(), weekday, hour, weather, userTypeCode
				);
		return prediction.map(value -> new RoutePlan(route, path.get(), value));
	}

	private Optional<List<RouteStopEntity>> findPath(String routeId, String originStopId, String destinationStopId) {
		List<RouteStopEntity> origins = routeStopRepository
				.findByRoute_IdAndStop_IdOrderByStopOrder(routeId, originStopId);
		List<RouteStopEntity> destinations = routeStopRepository
				.findByRoute_IdAndStop_IdOrderByStopOrder(routeId, destinationStopId);
		int[] selected = origins.stream()
				.flatMapToInt(origin -> destinations.stream()
						.filter(destination -> destination.getStopOrder() > origin.getStopOrder())
						.mapToInt(destination -> encodePair(origin.getStopOrder(), destination.getStopOrder())))
				.boxed()
				.min(Comparator.comparingInt(this::pairDistance))
				.map(this::decodePair)
				.orElse(null);
		if (selected == null) {
			return Optional.empty();
		}
		return Optional.of(routeStopRepository.findByRoute_IdAndStopOrderBetweenOrderByStopOrder(
				routeId, selected[0], selected[1]
		));
	}

	private List<BusArrival> arrivals(RoutePlan plan) {
		try {
			ArrivalLookupResult result = arrivalService.getArrivals(
					plan.path().getFirst().getStop().getId(),
					plan.route().getId(),
					plan.path().getFirst().getStopOrder()
			);
			return result.arrivals();
		} catch (TopisApiException exception) {
			log.warn("Arrival lookup skipped routeId={} reason={}", plan.route().getId(), exception.reason());
			return List.of();
		}
	}

	private JourneyRouteResponse toResponse(
			RoutePlan plan,
			BusArrival arrival,
			String tripId,
			boolean includeStandingPrediction
	) {
		PredictionEntity prediction = plan.prediction();
		int travelMinutes = toMinutes(prediction.getTravelSeconds());
		if (!includeStandingPrediction) {
			return new JourneyRouteResponse(
					tripId, plan.route().getId(), plan.route().getNumber(), direction(plan.route(), arrival),
					arrival.vehicleType(), arrival.lowFloor(), arrival.arrivalMinutes(), travelMinutes,
					null, null, null
			);
		}

		StandingBurdenLevel burdenLevel = burdenLevel(prediction.getRiskLevel());
		List<JourneySegmentResponse> segments = createSegments(
				plan.path(), travelMinutes, prediction.getStandingSeconds(), burdenLevel
		);
		int standingBurdenMinutes = segments.stream()
				.filter(segment -> segment.congestionLevel() != CongestionLevel.RELAXED)
				.mapToInt(JourneySegmentResponse::durationMinutes)
				.sum();
		return new JourneyRouteResponse(
				tripId, plan.route().getId(), plan.route().getNumber(), direction(plan.route(), arrival),
				arrival.vehicleType(), arrival.lowFloor(), arrival.arrivalMinutes(), travelMinutes,
				standingBurdenMinutes, burdenLevel, segments
		);
	}

	private List<JourneySegmentResponse> createSegments(
			List<RouteStopEntity> path,
			int travelMinutes,
			int standingSeconds,
			StandingBurdenLevel burdenLevel
	) {
		int segmentCount = path.size() - 1;
		if (segmentCount <= 0) {
			return List.of();
		}
		int[] durations = distributeMinutes(path, travelMinutes);
		List<JourneySegmentResponse> segments = new ArrayList<>(segmentCount);
		int elapsedSeconds = 0;
		for (int index = 0; index < segmentCount; index++) {
			RouteStopEntity from = path.get(index);
			RouteStopEntity to = path.get(index + 1);
			boolean standing = standingSeconds > elapsedSeconds;
			CongestionLevel congestion = standing
					? congestionLevel(burdenLevel)
					: CongestionLevel.RELAXED;
			segments.add(new JourneySegmentResponse(
					from.getStop().getId(), from.getStop().getName(),
					to.getStop().getId(), to.getStop().getName(),
					durations[index], congestion
			));
			elapsedSeconds += durations[index] * 60;
		}
		return segments;
	}

	private int[] distributeMinutes(List<RouteStopEntity> path, int totalMinutes) {
		int segmentCount = path.size() - 1;
		double[] weights = new double[segmentCount];
		double totalWeight = 0;
		for (int index = 0; index < segmentCount; index++) {
			Integer distance = path.get(index).getSectionDistance();
			weights[index] = distance == null || distance <= 0 ? 1 : distance;
			totalWeight += weights[index];
		}
		int[] durations = new int[segmentCount];
		double cumulativeWeight = 0;
		int allocated = 0;
		for (int index = 0; index < segmentCount; index++) {
			cumulativeWeight += weights[index];
			int boundary = (int) Math.round(totalMinutes * cumulativeWeight / totalWeight);
			durations[index] = boundary - allocated;
			allocated = boundary;
		}
		return durations;
	}

	private boolean isSufficient(RoutePlan plan) {
		PredictionEntity prediction = plan.prediction();
		return sampleCount(plan) >= properties.getMinSampleCount()
				&& prediction.getStandingSeconds() != null
				&& prediction.getStandingSeconds() >= 0
				&& prediction.getRiskLevel() != null
				&& prediction.getTravelSeconds() > 0;
	}

	private int sampleCount(RoutePlan plan) {
		return properties.getSampleBasis() == AppProperties.Prediction.SampleBasis.OD_PAIR
				? plan.prediction().getOdSampleCount()
				: plan.prediction().getBoardingSampleCount();
	}

	private PredictionConfidence confidence(int sampleCount) {
		if (sampleCount >= properties.getHighConfidenceSampleCount()) {
			return PredictionConfidence.HIGH;
		}
		if (sampleCount >= properties.getMediumConfidenceSampleCount()) {
			return PredictionConfidence.MEDIUM;
		}
		if (sampleCount >= properties.getMinSampleCount()) {
			return PredictionConfidence.LOW;
		}
		return PredictionConfidence.UNAVAILABLE;
	}

	private String resolveUserType(String requestedCode) {
		if (properties.getUserTypeMode() == AppProperties.Prediction.UserTypeMode.FIXED) {
			return properties.getFixedUserTypeCode();
		}
		String selected = requestedCode == null || requestedCode.isBlank()
				? properties.getFixedUserTypeCode()
				: requestedCode;
		if (!SELECTABLE_USER_TYPES.contains(selected)) {
			throw new ApiException(ErrorCode.INVALID_REQUEST);
		}
		return selected;
	}

	private StopEntity getStop(String stopId) {
		return stopRepository.findById(stopId)
				.orElseThrow(() -> new ApiException(ErrorCode.STOP_NOT_FOUND));
	}

	private static String direction(RouteEntity route, BusArrival arrival) {
		if (arrival.direction() != null && !arrival.direction().isBlank()) {
			return arrival.direction();
		}
		return route.getEndStopName() == null ? null : route.getEndStopName() + " 방면";
	}

	private static StandingBurdenLevel burdenLevel(String riskLevel) {
		return StandingBurdenLevel.valueOf(riskLevel);
	}

	private static CongestionLevel congestionLevel(StandingBurdenLevel burdenLevel) {
		return switch (burdenLevel) {
			case LOW -> CongestionLevel.NORMAL;
			case MEDIUM -> CongestionLevel.CROWDED;
			case HIGH -> CongestionLevel.VERY_CROWDED;
		};
	}

	private static int toMinutes(int seconds) {
		return seconds <= 0 ? 0 : (seconds + 59) / 60;
	}

	private static String uniqueTripId(String candidate, String routeId, Set<String> used) {
		if (used.add(candidate)) {
			return candidate;
		}
		String routeScoped = routeId + ":" + candidate;
		if (used.add(routeScoped)) {
			return routeScoped;
		}
		int sequence = 2;
		while (!used.add(routeScoped + ":" + sequence)) {
			sequence++;
		}
		return routeScoped + ":" + sequence;
	}

	private static String koreanWeekday(DayOfWeek dayOfWeek) {
		return switch (dayOfWeek) {
			case MONDAY -> "월";
			case TUESDAY -> "화";
			case WEDNESDAY -> "수";
			case THURSDAY -> "목";
			case FRIDAY -> "금";
			case SATURDAY -> "토";
			case SUNDAY -> "일";
		};
	}

	private static int encodePair(int originOrder, int destinationOrder) {
		return originOrder * 100_000 + destinationOrder;
	}

	private int pairDistance(int encodedPair) {
		int[] pair = decodePair(encodedPair);
		return pair[1] - pair[0];
	}

	private int[] decodePair(int encodedPair) {
		return new int[] {encodedPair / 100_000, encodedPair % 100_000};
	}

	private record RoutePlan(
			RouteEntity route,
			List<RouteStopEntity> path,
			PredictionEntity prediction
	) {
	}
}
