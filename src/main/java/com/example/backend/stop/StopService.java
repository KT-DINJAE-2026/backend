package com.example.backend.stop;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.example.backend.config.AppProperties;
import com.example.backend.domain.RouteEntity;
import com.example.backend.domain.StopEntity;
import com.example.backend.error.ApiException;
import com.example.backend.error.ErrorCode;
import com.example.backend.repository.RouteRepository;
import com.example.backend.repository.StopRepository;
import com.example.backend.repository.StopRouteProjection;
import com.example.backend.stop.dto.CurrentStopResponse;
import com.example.backend.stop.dto.DestinationStopResponse;
import com.example.backend.stop.dto.LocationResponse;
import com.example.backend.stop.dto.ServedRouteResponse;
import com.example.backend.stop.dto.StopContextResponse;
import com.example.backend.stop.dto.StopSearchResponse;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정류장 context와 목적지 검색 결과를 FE 계약 형식으로 조합한다.
 *
 * <p>검색 결과의 존재와 직통 가능 여부는 별개이다. 정류장명·ARS 검색에서는 직통이 없어도
 * 정류장을 반환하고 {@code servedRoutes: []}로 표시해 FE가 검색 실패와 직통 없음 화면을 구분한다.</p>
 */
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
			// 설정된 데모·초기 목적지가 없을 때만 실제 정방향 도달 후보를 제한 개수만 조회한다.
			destinationStops = stopRepository.findReachableStops(
					stopId,
					PageRequest.of(0, CONTEXT_DESTINATION_LIMIT)
			).getContent();
		}

		RouteLookup routeLookup = loadRoutes(currentStop.getId(), destinationStops, true);
		List<DestinationStopResponse> destinations = destinationStops.stream()
				.map(destination -> toDestination(destination, routeLookup))
				.toList();

		return new StopContextResponse(
				OffsetDateTime.now(clock),
				toCurrentStop(currentStop, directionFor(
						List.of(),
						routeLookup.servingRoutes().getOrDefault(currentStop.getId(), List.of())
				)),
				destinations
		);
	}

	public StopSearchResponse search(String originStopId, String query) {
		StopEntity originStop = getStop(originStopId);
		String normalizedQuery = query.strip();
		/*
		 * 정확한 노선 번호는 그 노선의 정방향 도착 후보만 노선 순서로 보여준다.
		 * 그 외 검색어는 전체 정류장을 검색해 직통이 없는 정류장도 결과에 유지한다.
		 */
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
		// 노선 조인으로 같은 정류장이 중복될 수 있어 검색 순서를 유지한 채 표준 ID로 제거한다.
		Map<String, StopEntity> uniqueCandidates = new LinkedHashMap<>();
		candidates.forEach(stop -> uniqueCandidates.putIfAbsent(stop.getId(), stop));
		List<StopEntity> destinationStops = uniqueCandidates.values().stream()
				.filter(stop -> !stop.getId().equals(originStopId))
				.toList();
		RouteLookup routeLookup = loadRoutes(originStop.getId(), destinationStops, false);
		List<DestinationStopResponse> destinations = destinationStops.stream()
				.map(stop -> toDestination(stop, routeLookup))
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
		// findAllById 결과 순서는 보장되지 않으므로 설정 파일에 적힌 ID 순서로 다시 조립한다.
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

	private DestinationStopResponse toDestination(StopEntity destination, RouteLookup routeLookup) {
		List<RouteEntity> routes = routeLookup.directRoutes().getOrDefault(destination.getId(), List.of());
		List<ServedRouteResponse> servedRoutes = routes.stream()
				.map(route -> new ServedRouteResponse(route.getId(), route.getNumber()))
				.toList();
		String direction = directionFor(
				routes,
				routeLookup.servingRoutes().getOrDefault(destination.getId(), List.of())
		);

		return new DestinationStopResponse(
				destination.getId(),
				destination.getArsId(),
				destination.getName(),
				direction,
				servedRoutes,
				toLocation(destination)
		);
	}

	private String directionFor(List<RouteEntity> preferredRoutes, List<RouteEntity> fallbackRoutes) {
		// 실제 승강장 방향 데이터가 없어 현재는 첫 번째 관련 노선의 종점명을 대표 방향으로 사용한다.
		List<RouteEntity> routes = preferredRoutes.isEmpty()
				? fallbackRoutes
				: preferredRoutes;
		return routes.stream()
				.map(RouteEntity::getEndStopName)
				.filter(value -> value != null && !value.isBlank())
				.findFirst()
				.map(value -> value + " 방면")
				.orElse(null);
	}

	/** 후보 수와 관계없이 직통 노선과 경유 노선을 각각 한 번의 벌크 쿼리로 준비한다. */
	private RouteLookup loadRoutes(
			String originStopId,
			List<StopEntity> destinationStops,
			boolean includeOriginForDirection
	) {
		List<String> destinationIds = destinationStops.stream()
				.map(StopEntity::getId)
				.distinct()
				.toList();
		Map<String, List<RouteEntity>> directRoutes = destinationIds.isEmpty()
				? Map.of()
				: groupRoutes(routeRepository.findDirectRoutesForStops(originStopId, destinationIds));

		LinkedHashSet<String> servingStopIds = new LinkedHashSet<>(destinationIds);
		if (includeOriginForDirection) {
			servingStopIds.add(originStopId);
		}
		Map<String, List<RouteEntity>> servingRoutes = servingStopIds.isEmpty()
				? Map.of()
				: groupRoutes(routeRepository.findRoutesServingStops(List.copyOf(servingStopIds)));
		return new RouteLookup(directRoutes, servingRoutes);
	}

	private Map<String, List<RouteEntity>> groupRoutes(List<StopRouteProjection> rows) {
		Map<String, List<RouteEntity>> groupedRoutes = new LinkedHashMap<>();
		for (StopRouteProjection row : rows) {
			groupedRoutes.computeIfAbsent(row.getStopId(), ignored -> new ArrayList<>())
					.add(row.getRoute());
		}
		return groupedRoutes;
	}

	private LocationResponse toLocation(StopEntity stop) {
		if (stop.getLatitude() == null || stop.getLongitude() == null) {
			// 원천 기반정보에 좌표가 없으면 불완전한 location 객체 대신 필드 전체를 생략한다.
			return null;
		}
		return new LocationResponse(stop.getLatitude(), stop.getLongitude());
	}

	private record RouteLookup(
			Map<String, List<RouteEntity>> directRoutes,
			Map<String, List<RouteEntity>> servingRoutes
	) {
	}
}
