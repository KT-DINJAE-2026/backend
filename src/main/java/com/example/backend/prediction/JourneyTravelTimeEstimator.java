package com.example.backend.prediction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.backend.arrival.BusArrival;
import com.example.backend.arrival.RouteArrivalSnapshot;
import com.example.backend.domain.RouteStopEntity;

import org.springframework.stereotype.Component;

/** 노선 전체 도착예정의 인접 정류장 ETA 차이로 승차 후 구간 이동시간을 계산한다. */
@Component
public class JourneyTravelTimeEstimator {

	private static final int DEFAULT_SEGMENT_SECONDS = 120;
	private static final double FALLBACK_SPEED_METERS_PER_SECOND = 15.0d / 3.6d;
	private static final int MAX_REASONABLE_SEGMENT_SECONDS = 30 * 60;

	public TravelEstimate estimate(
			List<RouteStopEntity> path,
			RouteArrivalSnapshot snapshot
	) {
		if (path.size() < 2) {
			return new TravelEstimate(0, 0, List.of());
		}

		List<RawSegment> rawSegments = new ArrayList<>(path.size() - 1);
		int totalSeconds = 0;
		for (int index = 0; index < path.size() - 1; index++) {
			RouteStopEntity from = path.get(index);
			RouteStopEntity to = path.get(index + 1);
			int seconds = liveSegmentSeconds(from, to, snapshot);
			rawSegments.add(new RawSegment(from, to, seconds));
			totalSeconds += seconds;
		}

		int totalMinutes = ceilingMinutes(totalSeconds);
		List<SegmentEstimate> segments = toMinuteSegments(rawSegments);
		return new TravelEstimate(totalSeconds, totalMinutes, segments);
	}

	private int liveSegmentSeconds(
			RouteStopEntity from,
			RouteStopEntity to,
			RouteArrivalSnapshot snapshot
	) {
		Map<String, Integer> fromEta = etaByTrip(snapshot.arrivalsAt(from.getStopOrder()));
		List<Integer> differences = snapshot.arrivalsAt(to.getStopOrder()).stream()
				.filter(arrival -> fromEta.containsKey(arrival.tripId()))
				.map(arrival -> arrival.arrivalSeconds() - fromEta.get(arrival.tripId()))
				.filter(seconds -> seconds > 0 && seconds <= MAX_REASONABLE_SEGMENT_SECONDS)
				.sorted()
				.toList();
		if (!differences.isEmpty()) {
			// 두 차량 값이 모두 있으면 큰 이상치 한쪽에 치우치지 않도록 중앙값을 사용한다.
			return differences.get(differences.size() / 2);
		}
		return distanceFallbackSeconds(to.getSectionDistance());
	}

	private Map<String, Integer> etaByTrip(List<BusArrival> arrivals) {
		Map<String, Integer> eta = new HashMap<>();
		for (BusArrival arrival : arrivals) {
			if (arrival.arrivalSeconds() > 0) {
				eta.put(arrival.tripId(), arrival.arrivalSeconds());
			}
		}
		return eta;
	}

	private int distanceFallbackSeconds(Integer sectionDistanceMeters) {
		if (sectionDistanceMeters == null || sectionDistanceMeters <= 0) {
			return DEFAULT_SEGMENT_SECONDS;
		}
		int seconds = (int) Math.ceil(sectionDistanceMeters / FALLBACK_SPEED_METERS_PER_SECOND);
		return Math.min(MAX_REASONABLE_SEGMENT_SECONDS, Math.max(1, seconds));
	}

	private List<SegmentEstimate> toMinuteSegments(List<RawSegment> rawSegments) {
		List<SegmentEstimate> estimates = new ArrayList<>(rawSegments.size());
		int elapsedSeconds = 0;
		int elapsedMinutes = 0;
		for (RawSegment segment : rawSegments) {
			elapsedSeconds += segment.durationSeconds();
			int endMinutes = ceilingMinutes(elapsedSeconds);
			estimates.add(new SegmentEstimate(
					segment.from(),
					segment.to(),
					segment.durationSeconds(),
					endMinutes - elapsedMinutes
			));
			elapsedMinutes = endMinutes;
		}
		return List.copyOf(estimates);
	}

	private static int ceilingMinutes(int seconds) {
		return seconds <= 0 ? 0 : (seconds + 59) / 60;
	}

	private record RawSegment(RouteStopEntity from, RouteStopEntity to, int durationSeconds) {
	}

	public record SegmentEstimate(
			RouteStopEntity from,
			RouteStopEntity to,
			int durationSeconds,
			int durationMinutes
	) {
	}

	public record TravelEstimate(
			int totalSeconds,
			int totalMinutes,
			List<SegmentEstimate> segments
	) {

		public TravelEstimate {
			segments = List.copyOf(segments);
		}
	}
}
