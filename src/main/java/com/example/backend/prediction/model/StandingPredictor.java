package com.example.backend.prediction.model;

import com.example.backend.prediction.feature.PredictionModelInput;

/** 차량 한 대의 승차 조건으로 입석 여부와 입석시간을 예측한다. */
public interface StandingPredictor {

	StandingPrediction predict(PredictionModelInput input);
}
