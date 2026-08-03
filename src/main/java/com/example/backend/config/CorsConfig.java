package com.example.backend.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

	private static final List<String> ALLOWED_ORIGINS = List.of(
			"http://localhost:5173",
			"https://kd-dinjae-2026-fe.vercel.app"
	);

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/api/**")
				.allowedOrigins(ALLOWED_ORIGINS.toArray(String[]::new))
				.allowedMethods(
						HttpMethod.GET.name(),
						HttpMethod.POST.name(),
						HttpMethod.OPTIONS.name()
				)
				.allowedHeaders(HttpHeaders.ACCEPT, HttpHeaders.CONTENT_TYPE);
	}
}
