package com.example.backend.prediction.model;

import com.example.backend.config.AppProperties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 입석 예측 모델을 기동 시 한 번만 적재해 애플리케이션 전역에서 공유한다. */
@Configuration
public class StandingModelConfig {

	@Bean
	public StandingModels standingModels(AppProperties appProperties) {
		return StandingModels.load(appProperties.getModel());
	}
}
