package com.example.backend.arrival;

public record BusArrival(
		String tripId,
		String routeId,
		String routeNumber,
		String direction,
		String vehicleNumber,
		String vehicleType,
		boolean lowFloor,
		int arrivalSeconds,
		int arrivalMinutes,
		String arrivalMessage,
		boolean lastBus,
		boolean full,
		boolean detour
) {
}
