package com.example.backend.stop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import com.example.backend.domain.RouteEntity;
import com.example.backend.domain.RouteStopEntity;
import com.example.backend.domain.StopEntity;
import com.example.backend.error.ApiException;
import com.example.backend.error.ErrorCode;
import com.example.backend.repository.RouteRepository;
import com.example.backend.repository.RouteStopRepository;
import com.example.backend.repository.StopRepository;
import com.example.backend.stop.dto.StopContextResponse;
import com.example.backend.stop.dto.StopSearchResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "app.api.initial-destination-stop-ids[0]=121000021")
@Transactional
class StopServiceIntegrationTests {

	@Autowired
	private StopService stopService;

	@Autowired
	private StopRepository stopRepository;

	@Autowired
	private RouteRepository routeRepository;

	@Autowired
	private RouteStopRepository routeStopRepository;

	private StopEntity origin;
	private StopEntity destination;

	@BeforeEach
	void setUp() {
		origin = stopRepository.save(new StopEntity(
				"121000019", "11100", "LOCAL-ORIGIN", "22019", "고속터미널", "서초구",
				new BigDecimal("37.506300"), new BigDecimal("127.005140")
		));
		destination = stopRepository.save(new StopEntity(
				"121000021", "11100", "LOCAL-DEST", "22021", "신반포역.세화여중고", "서초구",
				new BigDecimal("37.503420"), new BigDecimal("126.995720")
		));
		RouteEntity route = routeRepository.save(new RouteEntity(
				"100100027", "11100", "LOCAL-ROUTE", "148", "B", "방배동", "번동", "115"
		));
		routeStopRepository.save(new RouteStopEntity(route, origin, 10, 300));
		routeStopRepository.save(new RouteStopEntity(route, destination, 20, 250));
	}

	@Test
	void contextContainsConfiguredDestinationAndDirectRoute() {
		StopContextResponse response = stopService.getContext(origin.getId());

		assertThat(response.currentStop().stopName()).isEqualTo("고속터미널");
		assertThat(response.currentStop().directionDescription()).isEqualTo("번동 방면");
		assertThat(response.destinationStops()).hasSize(1);
		assertThat(response.destinationStops().getFirst().servedRoutes())
				.singleElement()
				.satisfies(route -> {
					assertThat(route.routeId()).isEqualTo("100100027");
					assertThat(route.routeNumber()).isEqualTo("148");
				});
		assertThat(response.generatedAt().getOffset().getTotalSeconds()).isEqualTo(9 * 60 * 60);
	}

	@Test
	void searchSupportsRouteNumber() {
		StopSearchResponse response = stopService.search(origin.getId(), "148");

		assertThat(response.destinationStops())
				.extracting(stop -> stop.stopId())
				.containsExactly(destination.getId());
	}

	@Test
	void reverseDirectionIsNotDirect() {
		StopSearchResponse response = stopService.search(destination.getId(), "고속터미널");

		assertThat(response.destinationStops()).singleElement()
				.satisfies(stop -> {
					assertThat(stop.servedRoutes()).isEmpty();
					assertThat(stop.directionDescription()).isEqualTo("번동 방면");
				});
	}

	@Test
	void missingOriginReturnsContractError() {
		assertThatThrownBy(() -> stopService.getContext("121999999"))
				.isInstanceOf(ApiException.class)
				.satisfies(exception -> assertThat(((ApiException) exception).errorCode())
						.isEqualTo(ErrorCode.STOP_NOT_FOUND));
	}
}
