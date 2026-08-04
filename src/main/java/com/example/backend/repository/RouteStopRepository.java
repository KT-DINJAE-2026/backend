package com.example.backend.repository;

import java.util.List;

import com.example.backend.domain.RouteStopEntity;

import org.springframework.data.jpa.repository.JpaRepository;

/** 노선 안에서 특정 정류장의 모든 순번과 선택 구간의 경유 목록을 조회한다. */
public interface RouteStopRepository extends JpaRepository<RouteStopEntity, Long> {

	List<RouteStopEntity> findByRoute_IdAndStop_IdOrderByStopOrder(
			String routeId,
			String stopId
	);

	/** 양 끝 순번을 포함해 실제 구간 생성에 사용할 정류장 목록을 순서대로 반환한다. */
	List<RouteStopEntity> findByRoute_IdAndStopOrderBetweenOrderByStopOrder(
			String routeId,
			int firstStopOrder,
			int lastStopOrder
	);
}
