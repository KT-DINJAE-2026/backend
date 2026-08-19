package com.example.backend.prediction;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.example.backend.arrival.ArrivalLookupStatus;
import com.example.backend.arrival.BusArrival;
import com.example.backend.arrival.RouteArrivalProvider;
import com.example.backend.arrival.RouteArrivalSnapshot;
import com.example.backend.domain.RouteDirectionDescription;
import com.example.backend.domain.RouteEntity;
import com.example.backend.domain.RouteStopEntity;
import com.example.backend.domain.StopEntity;
import com.example.backend.error.ApiException;
import com.example.backend.error.ErrorCode;
import com.example.backend.prediction.JourneyTravelTimeEstimator.SegmentEstimate;
import com.example.backend.prediction.JourneyTravelTimeEstimator.TravelEstimate;
import com.example.backend.prediction.dto.JourneyPredictionRequest;
import com.example.backend.prediction.dto.JourneyPredictionResponse;
import com.example.backend.prediction.dto.JourneyRouteResponse;
import com.example.backend.prediction.dto.JourneySegmentResponse;
import com.example.backend.prediction.dto.PredictionBasisResponse;
import com.example.backend.prediction.feature.PredictionModelInput;
import com.example.backend.prediction.feature.PredictionModelInputFactory;
import com.example.backend.prediction.model.StandingPrediction;
import com.example.backend.prediction.model.StandingPredictionStatus;
import com.example.backend.prediction.model.StandingPredictor;
import com.example.backend.repository.RouteRepository;
import com.example.backend.repository.RouteStopRepository;
import com.example.backend.repository.StopRepository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** TOPIS 도착예정과 PMML 입석 추론을 결합해 운영 여정 응답을 만든다. */
@Service
@ConditionalOnProperty(prefix = "app.demo", name = "enabled", havingValue = "false", matchIfMissing = true)
@Transactional(readOnly = true)
public class LiveJourneyPredictionService implements JourneyPredictionService {

	private static final String NO_ARRIVAL_REASON = "NO_REALTIME_ARRIVAL_DATA";
	private static final String MODEL_UNAVAILABLE_REASON = "MODEL_UNAVAILABLE";
	private static final String OUT_OF_DOMAIN_REASON = "NOT_ENOUGH_HISTORICAL_SAMPLES";

	private final StopRepository stopRepository;
	private final RouteRepository routeRepository;
	private final RouteStopRepository routeStopRepository;
	private final RouteArrivalProvider arrivalProvider;
	private final PredictionModelInputFactory inputFactory;
	private final StandingPredictor standingPredictor;
	private final JourneyTravelTimeEstimator travelTimeEstimator;
	private final Clock clock;

	public LiveJourneyPredictionService(
			StopRepository stopRepository,
			RouteRepository routeRepository,
			RouteStopRepository routeStopRepository,
			RouteArrivalProvider arrivalProvider,
			PredictionModelInputFactory inputFactory,
			StandingPredictor standingPredictor,
			JourneyTravelTimeEstimator travelTimeEstimator,
			Clock clock
	) {
		this.stopRepository = stopRepository;
		this.routeRepository = routeRepository;
		this.routeStopRepository = routeStopRepository;
		this.arrivalProvider = arrivalProvider;
		this.inputFactory = inputFactory;
		this.standingPredictor = standingPredictor;
		this.travelTimeEstimator = travelTimeEstimator;
		this.clock = clock;
	}

	@Override
	public JourneyPredictionResponse create(JourneyPredictionRequest request) {
		StopEntity origin = stopRepository.getRequired(request.originStopId());
		StopEntity destination = stopRepository.getRequired(request.destinationStopId());
		List<RouteEntity> directRoutes = routeRepository.findDirectRoutes(origin.getId(), destination.getId());
		validateDirectRoutes(origin, destination, directRoutes);

		List<Candidate> candidates = new ArrayList<>();
		for (RouteEntity route : directRoutes) {
			findPath(route.getId(), origin.getId(), destination.getId())
					.ifPresent(path -> addCandidates(candidates, route, path, origin, destination));
		}
		candidates.sort(Comparator
				.comparingInt((Candidate candidate) -> candidate.arrival().arrivalSeconds())
				.thenComparing(candidate -> candidate.route().getNumber())
				.thenComparing(candidate -> candidate.arrival().tripId()));
		/*
		 * FE 계약은 여정 전체의 SUCCESS/INSUFFICIENT_DATA만 있고 차량별 상태는 없다.
		 * 하나라도 예측할 수 없으면 전체를 데이터 부족으로 표시하고 혼잡 필드를 일괄 생략한다.
		 * 그래도 모든 직통 노선의 도착·이동시간은 유지해 특정 노선을 숨기지 않는다.
		 */
		boolean allPredicted = !candidates.isEmpty()
				&& candidates.stream().allMatch(candidate -> candidate.prediction().isPredicted());
		JourneyStatus status = allPredicted ? JourneyStatus.SUCCESS : JourneyStatus.INSUFFICIENT_DATA;
		String reasonCode = status == JourneyStatus.SUCCESS ? null : insufficientReason(candidates);
		return new JourneyPredictionResponse(
				status,
				reasonCode,
				OffsetDateTime.now(clock),
				origin.getId(),
				destination.getId(),
				new PredictionBasisResponse(
						allPredicted ? PredictionConfidence.MEDIUM : PredictionConfidence.UNAVAILABLE
				),
				candidates.stream().map(candidate -> toResponse(candidate, allPredicted)).toList()
		);
	}

	private void addCandidates(
			List<Candidate> candidates,
			RouteEntity route,
			List<RouteStopEntity> path,
			StopEntity origin,
			StopEntity destination
	) {
		RouteArrivalSnapshot snapshot = arrivalProvider.getRouteArrivals(route.getId());
		if (snapshot.status() != ArrivalLookupStatus.AVAILABLE) {
			return;
		}
		TravelEstimate travel = travelTimeEstimator.estimate(path, snapshot);
		int originOrder = path.getFirst().getStopOrder();
		for (BusArrival arrival : snapshot.arrivalsAt(originOrder)) {
			OffsetDateTime boardingTime = snapshot.providedAt().plusSeconds(Math.max(0, arrival.arrivalSeconds()));
			PredictionModelInput input = inputFactory.createForStop(
					route.getId(),
					route.getNumber(),
					origin.getId(),
					destination.getId(),
					origin.getLatitude(),
					origin.getLongitude(),
					boardingTime
			);
			candidates.add(new Candidate(
					route,
					arrival,
					travel,
					standingPredictor.predict(input)
			));
		}
	}

	private JourneyRouteResponse toResponse(Candidate candidate, boolean includePrediction) {
		BusArrival arrival = candidate.arrival();
		StandingPrediction prediction = candidate.prediction();
		if (!includePrediction || !prediction.isPredicted()) {
			return new JourneyRouteResponse(
					arrival.tripId(), candidate.route().getId(), candidate.route().getNumber(),
					direction(arrival, candidate.route()), arrival.vehicleType(), arrival.lowFloor(),
					arrival.arrivalMinutes(), candidate.travel().totalMinutes(), null, null, null
			);
		}

		int standingSeconds = boundedStandingSeconds(prediction, candidate.travel().totalSeconds());
		StandingBurdenLevel burdenLevel = burdenLevel(prediction, standingSeconds);
		return new JourneyRouteResponse(
				arrival.tripId(),
				candidate.route().getId(),
				candidate.route().getNumber(),
				direction(arrival, candidate.route()),
				arrival.vehicleType(),
				arrival.lowFloor(),
				arrival.arrivalMinutes(),
				candidate.travel().totalMinutes(),
				ceilingMinutes(standingSeconds),
				burdenLevel,
				segments(candidate.travel().segments(), standingSeconds, burdenLevel)
		);
	}

	private List<JourneySegmentResponse> segments(
			List<SegmentEstimate> estimates,
			int standingSeconds,
			StandingBurdenLevel burdenLevel
	) {
		int elapsedSeconds = 0;
		List<JourneySegmentResponse> segments = new ArrayList<>(estimates.size());
		for (SegmentEstimate estimate : estimates) {
			boolean standing = elapsedSeconds < standingSeconds;
			segments.add(new JourneySegmentResponse(
					estimate.from().getStop().getId(),
					estimate.from().getStop().getName(),
					estimate.to().getStop().getId(),
					estimate.to().getStop().getName(),
					estimate.durationMinutes(),
					standing ? congestionLevel(burdenLevel) : CongestionLevel.RELAXED
			));
			elapsedSeconds += estimate.durationSeconds();
		}
		return List.copyOf(segments);
	}

	private void validateDirectRoutes(
			StopEntity origin,
			StopEntity destination,
			List<RouteEntity> directRoutes
	) {
		if (!directRoutes.isEmpty()) {
			return;
		}
		if (!routeRepository.findRoutesConnectingStops(origin.getId(), destination.getId()).isEmpty()) {
			throw new ApiException(ErrorCode.STOP_DIRECTION_MISMATCH);
		}
		throw new ApiException(ErrorCode.NO_DIRECT_ROUTE);
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

	private static int boundedStandingSeconds(StandingPrediction prediction, int travelSeconds) {
		if (!Boolean.TRUE.equals(prediction.standing()) || prediction.standingSeconds() == null) {
			return 0;
		}
		int predictedSeconds = (int) Math.ceil(prediction.standingSeconds());
		return Math.min(Math.max(0, predictedSeconds), Math.max(0, travelSeconds));
	}

	private static StandingBurdenLevel burdenLevel(
			StandingPrediction prediction,
			int standingSeconds
	) {
		if (!Boolean.TRUE.equals(prediction.standing()) || standingSeconds == 0) {
			return StandingBurdenLevel.LOW;
		}
		return standingSeconds <= 5 * 60 ? StandingBurdenLevel.MEDIUM : StandingBurdenLevel.HIGH;
	}

	private static CongestionLevel congestionLevel(StandingBurdenLevel burdenLevel) {
		return switch (burdenLevel) {
			case LOW -> CongestionLevel.NORMAL;
			case MEDIUM -> CongestionLevel.CROWDED;
			case HIGH -> CongestionLevel.VERY_CROWDED;
		};
	}

	private static String direction(BusArrival arrival, RouteEntity route) {
		return arrival.direction() == null || arrival.direction().isBlank()
				? RouteDirectionDescription.fromEndStopName(route.getEndStopName())
				: arrival.direction();
	}

	private static int ceilingMinutes(int seconds) {
		return seconds <= 0 ? 0 : (seconds + 59) / 60;
	}

	private static String insufficientReason(List<Candidate> candidates) {
		if (candidates.isEmpty()) {
			return NO_ARRIVAL_REASON;
		}
		return candidates.stream().anyMatch(
				candidate -> candidate.prediction().status() == StandingPredictionStatus.MODEL_UNAVAILABLE
		) ? MODEL_UNAVAILABLE_REASON : OUT_OF_DOMAIN_REASON;
	}

	private record Candidate(
			RouteEntity route,
			BusArrival arrival,
			TravelEstimate travel,
			StandingPrediction prediction
	) {
	}
}
