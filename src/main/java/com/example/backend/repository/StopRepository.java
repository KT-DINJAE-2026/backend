package com.example.backend.repository;

import java.util.List;

import com.example.backend.domain.StopEntity;
import com.example.backend.error.ApiException;
import com.example.backend.error.ErrorCode;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 정류장명·ARS·노선 번호 검색과 출발지 이후 도달 가능한 정류장 조회를 담당한다. */
public interface StopRepository extends JpaRepository<StopEntity, String> {

	/** API에서 필수인 정류장을 조회하고 없으면 공통 정류장 오류를 반환한다. */
	default StopEntity getRequired(String stopId) {
		return findById(stopId)
				.orElseThrow(() -> new ApiException(ErrorCode.STOP_NOT_FOUND));
	}

	/** 직통 여부와 무관하게 검색어가 일치하는 정류장을 반환한다. */
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

	/** 출발 정류장과 같은 노선에서 더 큰 순번을 가진 모든 도착 후보를 반환한다. */
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

	/** 정확한 노선 번호 검색용으로, 해당 노선에서 출발지 이후 정류장만 노선 순서대로 반환한다. */
	@Query("""
			select destination.stop
			from RouteStopEntity origin
			join RouteStopEntity destination on destination.route = origin.route
			where origin.stop.id = :originStopId
				and destination.route.number = :routeNumber
				and destination.stopOrder > origin.stopOrder
			order by destination.stopOrder
			""")
	List<StopEntity> findReachableStopsByRouteNumber(
			@Param("originStopId") String originStopId,
			@Param("routeNumber") String routeNumber,
			Pageable pageable
	);
}
