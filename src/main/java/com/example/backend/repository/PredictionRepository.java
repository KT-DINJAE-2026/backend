package com.example.backend.repository;

import java.util.Optional;

import com.example.backend.domain.PredictionEntity;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PredictionRepository extends JpaRepository<PredictionEntity, Long> {

	Optional<PredictionEntity> findFirstByRoute_IdAndBoardingStop_IdAndAlightingStop_IdAndWeekdayAndHourAndWeatherAndUserTypeCode(
			String routeId,
			String boardingStopId,
			String alightingStopId,
			String weekday,
			int hour,
			String weather,
			String userTypeCode
	);
}
