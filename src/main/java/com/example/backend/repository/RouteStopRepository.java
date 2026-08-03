package com.example.backend.repository;

import java.util.List;

import com.example.backend.domain.RouteStopEntity;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteStopRepository extends JpaRepository<RouteStopEntity, Long> {

	List<RouteStopEntity> findByRoute_IdAndStop_IdOrderByStopOrder(
			String routeId,
			String stopId
	);

	List<RouteStopEntity> findByRoute_IdAndStopOrderBetweenOrderByStopOrder(
			String routeId,
			int firstStopOrder,
			int lastStopOrder
	);
}
