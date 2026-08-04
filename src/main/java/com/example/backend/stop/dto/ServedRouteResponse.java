package com.example.backend.stop.dto;

/** 출발지와 목적지를 정방향으로 함께 지나는 직통 노선의 식별 정보이다. */
public record ServedRouteResponse(
		String routeId,
		String routeNumber
) {
}
