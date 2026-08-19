package com.example.backend.prediction;

import com.example.backend.prediction.dto.JourneyPredictionRequest;
import com.example.backend.prediction.dto.JourneyPredictionResponse;

/** 출발·도착 정류장 요청을 직통 차량별 여정 분석 응답으로 변환한다. */
public interface JourneyPredictionService {

	JourneyPredictionResponse create(JourneyPredictionRequest request);
}
