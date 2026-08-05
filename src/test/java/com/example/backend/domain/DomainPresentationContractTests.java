package com.example.backend.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 공용화된 방향·차량 표시값이 기존 FE 계약 문자열을 유지하는지 검증한다. */
class DomainPresentationContractTests {

	@Test
	void keepsVehicleTypeDisplayNamesAndTopisMappings() {
		assertThat(BusVehicleType.LOW_FLOOR.displayName()).isEqualTo("저상버스");
		assertThat(BusVehicleType.STANDARD.displayName()).isEqualTo("일반버스");
		assertThat(BusVehicleType.ARTICULATED.displayName()).isEqualTo("굴절버스");
		assertThat(BusVehicleType.fromTopisCode("1")).isEqualTo(BusVehicleType.LOW_FLOOR);
		assertThat(BusVehicleType.fromTopisCode("2")).isEqualTo(BusVehicleType.ARTICULATED);
		assertThat(BusVehicleType.fromTopisCode("0")).isEqualTo(BusVehicleType.STANDARD);
	}

	@Test
	void keepsTheTemporaryEndStopDirectionFormat() {
		assertThat(RouteDirectionDescription.fromEndStopName("번동")).isEqualTo("번동 방면");
		assertThat(RouteDirectionDescription.fromEndStopName(null)).isNull();
		// JourneyTestDataService가 기존에 빈 종점명을 처리하던 결과도 그대로 보존한다.
		assertThat(RouteDirectionDescription.fromEndStopName("")).isEqualTo(" 방면");
	}
}
