package com.example.backend.prediction;

import com.example.backend.domain.StopEntity;

public interface WeatherProvider {

	String currentWeather(StopEntity stop);
}
