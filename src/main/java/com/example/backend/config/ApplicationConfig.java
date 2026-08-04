package com.example.backend.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 애플리케이션 전역에서 공유하는 설정 객체와 시간 기준을 구성한다. */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class ApplicationConfig {

	@Bean
	public Clock clock() {
		// Clock을 주입하면 테스트에서 응답 생성 시각과 캐시 만료 시각을 고정할 수 있다.
		return Clock.system(ZoneId.of("Asia/Seoul"));
	}
}
