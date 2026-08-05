package com.example.backend.repository;

import com.example.backend.domain.RouteEntity;

/** 여러 정류장의 관련 노선을 한 번에 조회할 때 정류장 ID와 노선을 함께 받는 투영이다. */
public interface StopRouteProjection {

	String getStopId();

	RouteEntity getRoute();
}
