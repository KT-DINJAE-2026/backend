package com.example.backend.prediction;

import com.example.backend.error.ApiErrorResponse;
import com.example.backend.prediction.dto.JourneyPredictionRequest;
import com.example.backend.prediction.dto.JourneyPredictionResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Journeys", description = "직통 버스 도착 및 입석 부담 예측")
@RestController
@RequestMapping(path = "/api/v1/journeys", produces = MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8")
public class JourneyPredictionController {

	private final JourneyPredictionService predictionService;

	public JourneyPredictionController(JourneyPredictionService predictionService) {
		this.predictionService = predictionService;
	}

	@Operation(summary = "직통 버스 여정 예측")
	@ApiResponse(responseCode = "200", description = "예측 성공 또는 과거 표본 부족 응답")
	@ApiResponse(
			responseCode = "404",
			description = "정류장 또는 직통 노선 없음",
			content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
	)
	@ApiResponse(
			responseCode = "409",
			description = "선택 방향으로 이동 불가",
			content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
	)
	@PostMapping(path = "/predictions", consumes = MediaType.APPLICATION_JSON_VALUE)
	public JourneyPredictionResponse predict(@Valid @RequestBody JourneyPredictionRequest request) {
		return predictionService.predict(request);
	}
}
