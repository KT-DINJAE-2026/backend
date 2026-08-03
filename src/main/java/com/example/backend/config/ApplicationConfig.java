package com.example.backend.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class ApplicationConfig {

	@Bean
	public Clock clock() {
		return Clock.system(ZoneId.of("Asia/Seoul"));
	}
}
