package com.example.backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI backendOpenApi() {
		return new OpenAPI().info(new Info()
				.title("교통약자 입석 위험 안내 API")
				.version("v1")
				.description("QR 정류장 진입, 직통 정류장 검색, 버스 도착 및 입석 부담 예측 API"));
	}
}
