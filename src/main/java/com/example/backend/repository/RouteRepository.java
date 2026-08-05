package com.example.backend.repository;

import java.util.List;

import com.example.backend.domain.RouteEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 정류장 경유 순서를 기준으로 노선과 직통 가능 여부를 조회한다. */
public interface RouteRepository extends JpaRepository<RouteEntity, String> {

	boolean existsByNumber(String number);

	@Query("""
			select distinct route
			from RouteEntity route
			join RouteStopEntity routeStop on routeStop.route = route
			where routeStop.stop.id = :stopId
			order by route.number
			""")
	List<RouteEntity> findRoutesServingStop(@Param("stopId") String stopId);

	/** 후보 정류장별 경유 노선을 한 번의 쿼리로 반환한다. */
	@Query("""
			select distinct routeStop.stop.id as stopId, route as route
			from RouteEntity route
			join RouteStopEntity routeStop on routeStop.route = route
			where routeStop.stop.id in :stopIds
			order by routeStop.stop.id, route.number
			""")
	List<StopRouteProjection> findRoutesServingStops(@Param("stopIds") List<String> stopIds);

	/**
	 * 출발 순번보다 도착 순번이 큰 노선만 반환한다.
	 * 같은 노선이 두 정류장을 모두 지나더라도 역방향이면 직통으로 보지 않는다.
	 */
	@Query("""
			select distinct route
			from RouteEntity route
			join RouteStopEntity origin on origin.route = route
			join RouteStopEntity destination on destination.route = route
			where origin.stop.id = :originStopId
				and destination.stop.id = :destinationStopId
				and destination.stopOrder > origin.stopOrder
			order by route.number
			""")
	List<RouteEntity> findDirectRoutes(
			@Param("originStopId") String originStopId,
			@Param("destinationStopId") String destinationStopId
	);

	/** 출발지 이후에 있는 후보 정류장별 직통 노선을 한 번의 쿼리로 반환한다. */
	@Query("""
			select distinct destination.stop.id as stopId, route as route
			from RouteEntity route
			join RouteStopEntity origin on origin.route = route
			join RouteStopEntity destination on destination.route = route
			where origin.stop.id = :originStopId
				and destination.stop.id in :destinationStopIds
				and destination.stopOrder > origin.stopOrder
			order by destination.stop.id, route.number
			""")
	List<StopRouteProjection> findDirectRoutesForStops(
			@Param("originStopId") String originStopId,
			@Param("destinationStopIds") List<String> destinationStopIds
	);

	/** 두 정류장의 경유 여부만 확인하며 방향은 무시한다. 역방향 오류 구분에 사용한다. */
	@Query("""
			select distinct route
			from RouteEntity route
			join RouteStopEntity firstStop on firstStop.route = route
			join RouteStopEntity secondStop on secondStop.route = route
			where firstStop.stop.id = :firstStopId
				and secondStop.stop.id = :secondStopId
			order by route.number
			""")
	List<RouteEntity> findRoutesConnectingStops(
			@Param("firstStopId") String firstStopId,
			@Param("secondStopId") String secondStopId
	);
}
