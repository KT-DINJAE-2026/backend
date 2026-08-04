package com.example.backend.controller;

import java.time.Instant;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 프로세스가 HTTP 요청에 응답하는지만 확인하는 가벼운 헬스 엔드포인트이다.
 *
 * <p>DB나 TOPIS 상태까지 검사하지 않으므로 운영 readiness 점검이 필요하면 별도 확장해야 한다.</p>
 */
@RestController
public class HealthController {

	@GetMapping("/health")
	public Map<String, Object> health() {
		return Map.of(
				"status", "UP",
				"timestamp", Instant.now().toString()
		);
	}

}
