package com.example.backend.repository;

import java.util.List;

import com.example.backend.domain.RouteEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
