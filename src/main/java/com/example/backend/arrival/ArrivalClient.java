package com.example.backend.arrival;

public interface ArrivalClient {

	ArrivalLookupResult getArrivals(String stopId, String routeId, int stopOrder);
}
