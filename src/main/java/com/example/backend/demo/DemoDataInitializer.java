package com.example.backend.demo;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.util.List;

import com.example.backend.domain.PredictionEntity;
import com.example.backend.domain.RouteEntity;
import com.example.backend.domain.RouteStopEntity;
import com.example.backend.domain.StopEntity;
import com.example.backend.repository.PredictionRepository;
import com.example.backend.repository.RouteRepository;
import com.example.backend.repository.RouteStopRepository;
import com.example.backend.repository.StopRepository;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix = "app.demo", name = "enabled", havingValue = "true")
public class DemoDataInitializer implements ApplicationRunner {

	private final StopRepository stopRepository;
	private final RouteRepository routeRepository;
	private final RouteStopRepository routeStopRepository;
	private final PredictionRepository predictionRepository;
	private final Clock clock;

	public DemoDataInitializer(
			StopRepository stopRepository,
			RouteRepository routeRepository,
			RouteStopRepository routeStopRepository,
			PredictionRepository predictionRepository,
			Clock clock
	) {
		this.stopRepository = stopRepository;
		this.routeRepository = routeRepository;
		this.routeStopRepository = routeStopRepository;
		this.predictionRepository = predictionRepository;
		this.clock = clock;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		StopEntity origin = saveStop("121000019", "22019", "고속터미널", "37.506300", "127.005140");
		StopEntity middle = saveStop("121000020", "22020", "고속터미널", "37.505160", "127.001050");
		StopEntity success = saveStop(
				"121000021", "22021", "신반포역.세화여중고", "37.503420", "126.995720"
		);
		StopEntity insufficientMiddle = saveStop(
				"121001343", "22045", "양재시민의숲", "37.476200", "127.038200"
		);
		StopEntity insufficient = saveStop(
				"121001344", "22046", "매헌시민의숲", "37.470360", "127.038750"
		);
		saveStop("121009999", "22999", "양재역", "37.484580", "127.034140");

		RouteEntity route148 = saveRoute("100100027", "148", "방배동", "번동", "115");
		RouteEntity route360 = saveRoute("100100057", "360", "송파", "여의도", "115");
		RouteEntity route452 = saveRoute("113000002", "452", "송파", "매헌시민의숲", "120");

		routeStopRepository.saveAll(List.of(
				new RouteStopEntity(route148, origin, 35, 300),
				new RouteStopEntity(route148, middle, 36, 700),
				new RouteStopEntity(route148, success, 37, 200),
				new RouteStopEntity(route360, origin, 27, 500),
				new RouteStopEntity(route360, success, 28, 300),
				new RouteStopEntity(route452, origin, 56, 8_000),
				new RouteStopEntity(route452, insufficientMiddle, 57, 2_000),
				new RouteStopEntity(route452, insufficient, 58, 500)
		));

		OffsetDateTime now = OffsetDateTime.now(clock);
		String weekday = koreanWeekday(now.getDayOfWeek());
		predictionRepository.saveAll(List.of(
				new PredictionEntity(
						route148, origin, success, weekday, now.getHour(), "맑음", "04",
						150, "MEDIUM", new BigDecimal("0.8120"), 150, 50, 600, "demo"
				),
				new PredictionEntity(
						route360, origin, success, weekday, now.getHour(), "맑음", "04",
						0, "LOW", new BigDecimal("0.9300"), 1_200, 300, 540, "demo"
				),
				new PredictionEntity(
						route452, origin, insufficient, weekday, now.getHour(), "맑음", "04",
						null, null, null, 1, 1, 1_200, "demo"
				)
		));
	}

	private StopEntity saveStop(String id, String arsId, String name, String latitude, String longitude) {
		return stopRepository.save(new StopEntity(
				id,
				"11100",
				"DEMO-" + id,
				arsId,
				name,
				"서초구",
				new BigDecimal(latitude),
				new BigDecimal(longitude)
		));
	}

	private RouteEntity saveRoute(
			String id,
			String number,
			String startStopName,
			String endStopName,
			String transportTypeCode
	) {
		return routeRepository.save(new RouteEntity(
				id,
				"11100",
				"DEMO-" + id,
				number,
				"B",
				startStopName,
				endStopName,
				transportTypeCode
		));
	}

	private static String koreanWeekday(DayOfWeek dayOfWeek) {
		return switch (dayOfWeek) {
			case MONDAY -> "월";
			case TUESDAY -> "화";
			case WEDNESDAY -> "수";
			case THURSDAY -> "목";
			case FRIDAY -> "금";
			case SATURDAY -> "토";
			case SUNDAY -> "일";
		};
	}
}
