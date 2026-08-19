package com.example.backend.arrival;

/** 여정 서비스에 캐시된 노선 전체 도착예정을 제공한다. */
@FunctionalInterface
public interface RouteArrivalProvider {

	RouteArrivalSnapshot getRouteArrivals(String routeId);
}
