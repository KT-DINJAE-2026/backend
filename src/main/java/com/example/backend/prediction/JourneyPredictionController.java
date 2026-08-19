package com.example.backend.prediction;

import com.example.backend.error.ApiErrorResponse;
import com.example.backend.prediction.dto.JourneyPredictionRequest;
import com.example.backend.prediction.dto.JourneyPredictionResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 출발·도착 정류장을 잇는 직통 버스의 여정 분석 API를 노출한다.
 *
 * <p>demo 프로필은 고정 계약 데이터를, 그 밖의 프로필은 TOPIS·PMML 실제 연동 결과를 사용한다.</p>
 */
@Tag(name = "Journeys", description = "직통 버스 도착 및 입석 부담 예측")
@RestController
@RequestMapping("/api/v1/journeys")
public class JourneyPredictionController {

	private final JourneyPredictionService predictionService;

	public JourneyPredictionController(JourneyPredictionService predictionService) {
		this.predictionService = predictionService;
	}

	@Operation(
			summary = "직통 버스 여정 분석",
			description = "운영 프로필은 TOPIS 실시간 도착정보와 PMML 입석 예측을 결합하고, "
					+ "demo 프로필은 FE 계약 검증용 고정 데이터를 반환합니다."
	)
	@ApiResponse(
			responseCode = "200",
			description = "예측 성공 또는 과거 표본 부족 응답",
			content = @Content(
					schema = @Schema(implementation = JourneyPredictionResponse.class),
						examples = {
							@ExampleObject(name = "SUCCESS", value = """
									{
									  "status":"SUCCESS",
									  "generatedAt":"2026-08-03T09:00:00+09:00",
									  "originStopId":"107000087",
									  "destinationStopId":"107000089",
									  "predictionBasis":{"confidence":"MEDIUM"},
									  "routes":[{
									    "tripId":"mock-trip-100100129-1405",
									    "routeId":"100100129",
									    "routeNumber":"1014",
									    "direction":"동묘앞 방면",
									    "vehicleType":"저상버스",
									    "isLowFloor":true,
									    "arrivalMinutes":5,
									    "travelMinutes":3,
									    "standingBurdenMinutes":0,
									    "standingBurdenLevel":"LOW",
									    "segments":[
									      {
									        "fromStopId":"107000087",
									        "fromStopName":"성북구청.성북경찰서",
									        "toStopId":"107000089",
									        "toStopName":"보문역2번출구",
									        "durationMinutes":3,
									        "congestionLevel":"RELAXED"
									      }
									    ]
									  }]
									}
									"""),
							@ExampleObject(name = "INSUFFICIENT_DATA", value = """
									{
									  "status":"INSUFFICIENT_DATA",
									  "reasonCode":"NOT_ENOUGH_HISTORICAL_SAMPLES",
									  "generatedAt":"2026-08-03T09:00:00+09:00",
									  "originStopId":"107000087",
									  "destinationStopId":"100000147",
									  "predictionBasis":{"confidence":"UNAVAILABLE"},
									  "routes":[{
									    "tripId":"mock-trip-100100129-1404",
									    "routeId":"100100129",
									    "routeNumber":"1014",
									    "direction":"동묘앞 방면",
									    "vehicleType":"저상버스",
									    "isLowFloor":true,
									    "arrivalMinutes":4,
									    "travelMinutes":10
									  }]
									}
									""")
					}
			)
	)
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
	@ApiResponse(
			responseCode = "502",
			description = "TOPIS·날씨·공휴일 상위 서비스 통신 또는 응답 오류",
			content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
	)
	@ApiResponse(
			responseCode = "503",
			description = "외부 API 인증 또는 서버 설정 오류",
			content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
	)
	@PostMapping(path = "/predictions", consumes = MediaType.APPLICATION_JSON_VALUE)
	public JourneyPredictionResponse predict(@Valid @RequestBody JourneyPredictionRequest request) {
		return predictionService.create(request);
	}
}
