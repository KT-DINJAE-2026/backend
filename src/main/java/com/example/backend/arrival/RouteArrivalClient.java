package com.example.backend.arrival;

/** 노선 전체 정류장의 실시간 도착예정을 한 번에 조회하는 외부 연동 경계이다. */
@FunctionalInterface
public interface RouteArrivalClient {

	RouteArrivalSnapshot getRouteArrivals(String routeId);
}
