package com.example.backend.stop;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.backend.config.AppProperties;
import com.example.backend.domain.RouteEntity;
import com.example.backend.domain.StopEntity;
import com.example.backend.error.ApiException;
import com.example.backend.error.ErrorCode;
import com.example.backend.repository.RouteRepository;
import com.example.backend.repository.StopRepository;
import com.example.backend.stop.dto.CurrentStopResponse;
import com.example.backend.stop.dto.DestinationStopResponse;
import com.example.backend.stop.dto.LocationResponse;
import com.example.backend.stop.dto.ServedRouteResponse;
import com.example.backend.stop.dto.StopContextResponse;
import com.example.backend.stop.dto.StopSearchResponse;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class StopService {

	private static final int CONTEXT_DESTINATION_LIMIT = 10;
	private static final int SEARCH_RESULT_LIMIT = 20;

	private final StopRepository stopRepository;
	private final RouteRepository routeRepository;
	private final AppProperties appProperties;
	private final Clock clock;

	public StopService(
			StopRepository stopRepository,
			RouteRepository routeRepository,
			AppProperties appProperties,
			Clock clock
	) {
		this.stopRepository = stopRepository;
		this.routeRepository = routeRepository;
		this.appProperties = appProperties;
		this.clock = clock;
	}

	public StopContextResponse getContext(String stopId) {
		StopEntity currentStop = getStop(stopId);
		List<StopEntity> destinationStops = configuredDestinationStops(stopId);
		if (destinationStops.isEmpty()) {
			destinationStops = stopRepository.findReachableStops(
					stopId,
					PageRequest.of(0, CONTEXT_DESTINATION_LIMIT)
			).getContent();
		}

		List<DestinationStopResponse> destinations = destinationStops.stream()
				.map(destination -> toDestination(currentStop, destination))
				.toList();

		return new StopContextResponse(
				OffsetDateTime.now(clock),
				toCurrentStop(currentStop, directionFor(currentStop.getId(), List.of())),
				destinations
		);
	}

	public StopSearchResponse search(String originStopId, String query) {
		StopEntity originStop = getStop(originStopId);
		String normalizedQuery = query.strip();
		List<StopEntity> candidates = routeRepository.existsByNumber(normalizedQuery)
				? stopRepository.findReachableStopsByRouteNumber(
						originStopId,
						normalizedQuery,
						PageRequest.of(0, SEARCH_RESULT_LIMIT)
				)
				: stopRepository.search(
						normalizedQuery,
						PageRequest.of(0, SEARCH_RESULT_LIMIT)
				).getContent();
		Map<String, StopEntity> uniqueCandidates = new LinkedHashMap<>();
		candidates.forEach(stop -> uniqueCandidates.putIfAbsent(stop.getId(), stop));
		List<DestinationStopResponse> destinations = uniqueCandidates.values().stream()
				.filter(stop -> !stop.getId().equals(originStopId))
				.map(stop -> toDestination(originStop, stop))
				.toList();
		return new StopSearchResponse(destinations);
	}

	private StopEntity getStop(String stopId) {
		return stopRepository.findById(stopId)
				.orElseThrow(() -> new ApiException(ErrorCode.STOP_NOT_FOUND));
	}

	private List<StopEntity> configuredDestinationStops(String originStopId) {
		List<String> configuredIds = appProperties.getApi().getInitialDestinationStopIds();
		if (configuredIds.isEmpty()) {
			return List.of();
		}
		Map<String, StopEntity> stopsById = new LinkedHashMap<>();
		stopRepository.findAllById(configuredIds).forEach(stop -> stopsById.put(stop.getId(), stop));
		return configuredIds.stream()
				.filter(id -> !id.equals(originStopId))
				.map(stopsById::get)
				.filter(stop -> stop != null)
				.toList();
	}

	private CurrentStopResponse toCurrentStop(StopEntity stop, String direction) {
		return new CurrentStopResponse(
				stop.getId(),
				stop.getArsId(),
				stop.getName(),
				direction,
				toLocation(stop)
		);
	}

	private DestinationStopResponse toDestination(StopEntity origin, StopEntity destination) {
		List<RouteEntity> routes = routeRepository.findDirectRoutes(origin.getId(), destination.getId());
		List<ServedRouteResponse> servedRoutes = routes.stream()
				.map(route -> new ServedRouteResponse(route.getId(), route.getNumber()))
				.toList();
		String direction = directionFor(destination.getId(), routes);

		return new DestinationStopResponse(
				destination.getId(),
				destination.getArsId(),
				destination.getName(),
				direction,
				servedRoutes,
				toLocation(destination)
		);
	}

	private String directionFor(String stopId, List<RouteEntity> preferredRoutes) {
		List<RouteEntity> routes = preferredRoutes.isEmpty()
				? routeRepository.findRoutesServingStop(stopId)
				: preferredRoutes;
		return routes.stream()
				.map(RouteEntity::getEndStopName)
				.filter(value -> value != null && !value.isBlank())
				.findFirst()
				.map(value -> value + " 방면")
				.orElse(null);
	}

	private LocationResponse toLocation(StopEntity stop) {
		if (stop.getLatitude() == null || stop.getLongitude() == null) {
			return null;
		}
		return new LocationResponse(stop.getLatitude(), stop.getLongitude());
	}
}
