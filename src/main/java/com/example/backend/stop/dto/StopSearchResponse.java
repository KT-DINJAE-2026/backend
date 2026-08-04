package com.example.backend.stop.dto;

import java.util.List;

/** 도착 정류장 검색 결과. 일치하는 정류장이 없으면 빈 배열을 반환한다. */
public record StopSearchResponse(
		List<DestinationStopResponse> destinationStops
) {
}
