package com.example.backend.repository;

import com.example.backend.domain.StopEntity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StopRepository extends JpaRepository<StopEntity, String> {

	@Query("""
			select distinct stop
			from StopEntity stop
			left join RouteStopEntity routeStop on routeStop.stop = stop
			left join routeStop.route route
			where lower(stop.name) like lower(concat('%', :query, '%'))
				or stop.arsId like concat('%', :query, '%')
				or lower(route.number) like lower(concat('%', :query, '%'))
			order by stop.name, stop.arsId
			""")
	Page<StopEntity> search(@Param("query") String query, Pageable pageable);

	@Query("""
			select distinct destination.stop
			from RouteStopEntity origin
			join RouteStopEntity destination on destination.route = origin.route
			where origin.stop.id = :originStopId
				and destination.stopOrder > origin.stopOrder
			order by destination.stop.name, destination.stop.arsId
			""")
	Page<StopEntity> findReachableStops(
			@Param("originStopId") String originStopId,
			Pageable pageable
	);

	@Query("""
			select distinct destination.stop
			from RouteStopEntity origin
			join RouteStopEntity destination on destination.route = origin.route
			where origin.stop.id = :originStopId
				and destination.route.number = :routeNumber
				and destination.stopOrder > origin.stopOrder
			order by destination.stopOrder
			""")
	Page<StopEntity> findReachableStopsByRouteNumber(
			@Param("originStopId") String originStopId,
			@Param("routeNumber") String routeNumber,
			Pageable pageable
	);
}
