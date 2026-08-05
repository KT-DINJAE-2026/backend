package com.example.backend.domain;

/** 노선 종점명을 현재 API의 임시 방향 문구로 변환한다. */
public final class RouteDirectionDescription {

	private RouteDirectionDescription() {
	}

	public static String fromEndStopName(String endStopName) {
		// 실제 승강장 방향 데이터가 없어 현재는 노선 종점명을 임시 방향 문구로 사용한다.
		return endStopName == null ? null : endStopName + " 방면";
	}
}
