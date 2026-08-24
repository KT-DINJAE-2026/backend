package com.example.backend.headway;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import com.example.backend.arrival.BusArrival;
import com.example.backend.arrival.RouteArrivalSnapshot;

import org.springframework.stereotype.Component;

/**
 * 한 정류장에 도착할 차량의 TOPIS ETA와 이전 스냅샷으로 실시간 배차간격을 계산한다.
 *
 * <p>두 번째 차량은 같은 스냅샷의 첫 번째 차량을 직전 차량으로 사용한다. 첫 번째 차량은 이전
 * 스냅샷에서 두 번째 차량으로 관측된 뒤 첫 번째로 이동한 경우에만 앞 차량의 마지막 ETA를 이어
 * 쓴다. 조회 사이에 차량 연결을 확인하지 못했거나 값이 비정상이면 추정하지 않고 결측을 반환한다.</p>
 */
@Component
public class RealtimeHeadwayResolver {

	private static final Duration HISTORY_TTL = Duration.ofHours(6);
	private static final long MAX_HEADWAY_SECONDS = HISTORY_TTL.toSeconds();
	private static final int MAX_HISTORY_ENTRIES = 10_000;

	private final Map<HistoryKey, HistoryEntry> histories = new ConcurrentHashMap<>();

	/**
	 * 출발 정류장의 도착 순서와 같은 크기의 실시간 배차간격 목록을 반환한다.
	 *
	 * @param snapshot 동일 노선 전체 정류장의 TOPIS 도착 스냅샷
	 * @param stopOrder 출발 정류장의 노선 내 순번
	 * @return 차량 순서별 직전 차량 간격. 확인할 수 없는 원소는 {@code null}
	 */
	public List<Long> headwaySeconds(RouteArrivalSnapshot snapshot, int stopOrder) {
		Objects.requireNonNull(snapshot, "snapshot은 null일 수 없습니다.");
		List<BusArrival> arrivals = snapshot.arrivalsAt(stopOrder);
		if (arrivals.isEmpty()) {
			return List.of();
		}

		OffsetDateTime observedAt = snapshot.providedAt();
		String routeId = snapshot.routeId();
		if (observedAt == null || routeId == null || routeId.isBlank() || stopOrder <= 0) {
			return missing(arrivals.size());
		}

		removeExpired(observedAt);
		HistoryKey key = new HistoryKey(routeId, stopOrder);
		if (!histories.containsKey(key) && histories.size() >= MAX_HISTORY_ENTRIES) {
			removeOldest();
		}
		AtomicReference<List<Long>> resolved = new AtomicReference<>();
		histories.compute(key, (ignored, previous) -> {
			if (previous != null && observedAt.isBefore(previous.observedAt())) {
				// 늦게 도착한 과거 응답은 최신 이력을 덮지 않고 현재 스냅샷 내부 간격만 계산한다.
				HistoryEntry standalone = update(null, observedAt, routeId, arrivals);
				resolved.set(standalone.headways());
				return previous;
			}
			HistoryEntry current = update(previous, observedAt, routeId, arrivals);
			resolved.set(current.headways());
			return current;
		});
		return resolved.get();
	}

	private HistoryEntry update(
			HistoryEntry previous,
			OffsetDateTime observedAt,
			String routeId,
			List<BusArrival> arrivals
	) {
		List<ArrivalObservation> observations = arrivals.stream()
				.map(arrival -> observation(arrival, observedAt, routeId))
				.toList();
		OffsetDateTime firstPredecessorAt = predecessorForFirst(previous, observations, observedAt);
		List<Long> headways = new ArrayList<>(observations.size());
		for (int index = 0; index < observations.size(); index++) {
			ArrivalObservation target = observations.get(index);
			if (index == 0) {
				headways.add(secondsBetween(firstPredecessorAt, target));
			} else {
				headways.add(secondsBetween(observations.get(index - 1), target));
			}
		}
		return new HistoryEntry(observedAt, observations, firstPredecessorAt, headways);
	}

	private OffsetDateTime predecessorForFirst(
			HistoryEntry previous,
			List<ArrivalObservation> current,
			OffsetDateTime observedAt
	) {
		if (previous == null || current.isEmpty() || expired(previous, observedAt)) {
			return null;
		}
		ArrivalObservation currentFirst = current.getFirst();
		if (!currentFirst.valid()) {
			return null;
		}

		ArrivalObservation previousFirst = previous.observations().getFirst();
		if (sameVehicle(previousFirst, currentFirst)) {
			return previous.firstPredecessorAt();
		}
		// 관측시각이 전진하지 않았는데 차량 순서가 바뀌면 신뢰할 수 없는 응답이다.
		if (!observedAt.isAfter(previous.observedAt())) {
			return null;
		}

		for (int index = 1; index < previous.observations().size(); index++) {
			if (sameVehicle(previous.observations().get(index), currentFirst)) {
				ArrivalObservation predecessor = previous.observations().get(index - 1);
				return validPair(predecessor, currentFirst) ? predecessor.arrivalAt() : null;
			}
		}
		return null;
	}

	private ArrivalObservation observation(
			BusArrival arrival,
			OffsetDateTime observedAt,
			String expectedRouteId
	) {
		if (arrival == null
				|| arrival.tripId() == null || arrival.tripId().isBlank()
				|| arrival.routeId() == null || arrival.routeId().isBlank()
				|| !Objects.equals(arrival.routeId(), expectedRouteId)
				|| arrival.arrivalSeconds() <= 0) {
			return ArrivalObservation.invalid();
		}
		return new ArrivalObservation(
				arrival.tripId(),
				arrival.routeId(),
				observedAt.plusSeconds(arrival.arrivalSeconds())
		);
	}

	private Long secondsBetween(ArrivalObservation previous, ArrivalObservation target) {
		if (!validPair(previous, target)) {
			return null;
		}
		return secondsBetween(previous.arrivalAt(), target);
	}

	private Long secondsBetween(OffsetDateTime previousArrivalAt, ArrivalObservation target) {
		if (previousArrivalAt == null || !target.valid()) {
			return null;
		}
		long seconds = Duration.between(previousArrivalAt, target.arrivalAt()).getSeconds();
		return seconds > 0 && seconds <= MAX_HEADWAY_SECONDS ? seconds : null;
	}

	private boolean validPair(ArrivalObservation previous, ArrivalObservation target) {
		return previous != null
				&& previous.valid()
				&& target.valid()
				&& Objects.equals(previous.routeId(), target.routeId())
				&& !Objects.equals(previous.tripId(), target.tripId());
	}

	private boolean sameVehicle(ArrivalObservation left, ArrivalObservation right) {
		return left != null
				&& left.valid()
				&& right.valid()
				&& Objects.equals(left.routeId(), right.routeId())
				&& Objects.equals(left.tripId(), right.tripId());
	}

	private boolean expired(HistoryEntry history, OffsetDateTime observedAt) {
		Duration age = Duration.between(history.observedAt(), observedAt);
		return age.isNegative() || age.compareTo(HISTORY_TTL) > 0;
	}

	private void removeExpired(OffsetDateTime observedAt) {
		histories.entrySet().removeIf(entry -> {
			Duration age = Duration.between(entry.getValue().observedAt(), observedAt);
			return !age.isNegative() && age.compareTo(HISTORY_TTL) > 0;
		});
	}

	private void removeOldest() {
		histories.entrySet().stream()
				.min(Comparator.comparing(entry -> entry.getValue().observedAt()))
				.ifPresent(entry -> histories.remove(entry.getKey(), entry.getValue()));
	}

	private static List<Long> missing(int size) {
		return Collections.nCopies(size, null);
	}

	private record HistoryKey(String routeId, int stopOrder) {
	}

	private record ArrivalObservation(String tripId, String routeId, OffsetDateTime arrivalAt) {

		private static ArrivalObservation invalid() {
			return new ArrivalObservation(null, null, null);
		}

		private boolean valid() {
			return tripId != null && routeId != null && arrivalAt != null;
		}
	}

	private record HistoryEntry(
			OffsetDateTime observedAt,
			List<ArrivalObservation> observations,
			OffsetDateTime firstPredecessorAt,
			List<Long> headways
	) {

		private HistoryEntry {
			observations = List.copyOf(observations);
			headways = Collections.unmodifiableList(new ArrayList<>(headways));
		}
	}
}
