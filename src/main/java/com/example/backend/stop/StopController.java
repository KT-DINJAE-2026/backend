package com.example.backend.stop;

import com.example.backend.error.ApiErrorResponse;
import com.example.backend.stop.dto.StopContextResponse;
import com.example.backend.stop.dto.StopSearchResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** QR 출발 정류장 context와 도착 정류장 검색 API를 노출하는 얇은 웹 계층이다. */
@Tag(name = "Stops", description = "정류장 진입 정보와 직통 노선 검색")
@Validated
@RestController
@RequestMapping("/api/v1/stops")
public class StopController {

	private final StopService stopService;

	public StopController(StopService stopService) {
		this.stopService = stopService;
	}

	@Operation(summary = "QR 출발 정류장 context 조회")
	@ApiResponse(responseCode = "200", description = "출발 정류장과 초기 도착 정류장 목록")
	@ApiResponse(
			responseCode = "404",
			description = "정류장 없음",
			content = @Content(
					schema = @Schema(implementation = ApiErrorResponse.class),
					examples = @ExampleObject(value = """
							{"code":"STOP_NOT_FOUND","message":"정류장 정보를 찾을 수 없습니다.","traceId":"7e91d8d5"}
							""")
			)
	)
	@GetMapping("/{stopId}/context")
	public StopContextResponse getContext(
			@PathVariable
			@Pattern(regexp = "\\d{9}", message = "stopId는 숫자 9자리여야 합니다.")
			String stopId
	) {
		return stopService.getContext(stopId);
	}

	@Operation(summary = "도착 정류장 검색", description = "정류장명, ARS 번호, 노선 번호로 검색합니다.")
	@ApiResponse(responseCode = "200", description = "검색 결과. 결과가 없으면 destinationStops는 빈 배열입니다.")
	@ApiResponse(
			responseCode = "400",
			description = "잘못된 검색 조건",
			content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
	)
	@GetMapping("/search")
	public StopSearchResponse search(
			@RequestParam
			@Pattern(regexp = "\\d{9}", message = "originStopId는 숫자 9자리여야 합니다.")
			String originStopId,
			@RequestParam
			@NotBlank
			@Size(max = 50)
			String query
	) {
		return stopService.search(originStopId, query);
	}
}
