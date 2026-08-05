package com.example.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** FE 개발 서버와 정식 Vercel 배포 주소에 한해 API 교차 출처 요청을 허용한다. */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

	private final AppProperties appProperties;

	public CorsConfig(AppProperties appProperties) {
		this.appProperties = appProperties;
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		// Preview 배포 주소는 매번 바뀌므로 현재 목록에 포함하지 않는다.
		registry.addMapping("/api/**")
				.allowedOrigins(appProperties.getCors().getAllowedOrigins().toArray(String[]::new))
				.allowedMethods(
						HttpMethod.GET.name(),
						HttpMethod.POST.name(),
						HttpMethod.OPTIONS.name()
				)
				.allowedHeaders(HttpHeaders.ACCEPT, HttpHeaders.CONTENT_TYPE);
	}
}
