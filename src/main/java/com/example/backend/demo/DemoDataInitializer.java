package com.example.backend.demo;

import java.math.BigDecimal;
import java.util.List;

import com.example.backend.domain.RouteEntity;
import com.example.backend.domain.RouteStopEntity;
import com.example.backend.domain.StopEntity;
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

	public DemoDataInitializer(
			StopRepository stopRepository,
			RouteRepository routeRepository,
			RouteStopRepository routeStopRepository
	) {
		this.stopRepository = stopRepository;
		this.routeRepository = routeRepository;
		this.routeStopRepository = routeStopRepository;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		StopEntity origin = saveStop(
				"107000087", "08177", "성북구청.성북경찰서", "37.5881513802", "127.0174306588"
		);
		StopEntity success = saveStop(
				"107000089", "08179", "보문역2번출구", "37.5858514183", "127.0189209428"
		);
		StopEntity middle = saveStop(
				"100000146", "01242", "동묘앞역", "37.5731800000", "127.0165500000"
		);
		StopEntity insufficient = saveStop(
				"100000147", "01243", "신설동역오거리", "37.5756947252", "127.0228414296"
		);
		saveStop("121009999", "22999", "직통노선없는정류장", "37.584580", "127.034140");

		RouteEntity route1014 = saveRoute("100100129", "1014", "성북구청", "동묘앞", "115");
		RouteEntity route152 = saveRoute("100100031", "152", "성북구청", "동대문", "115");
		RouteEntity route103 = saveRoute("100100008", "103", "성북구청", "동대문", "115");
		RouteEntity route142 = saveRoute("100100021", "142", "성북구청", "창신동", "115");

		routeStopRepository.saveAll(List.of(
				new RouteStopEntity(route1014, origin, 10, 300),
				new RouteStopEntity(route1014, success, 11, 500),
				new RouteStopEntity(route1014, middle, 12, 600),
				new RouteStopEntity(route1014, insufficient, 13, 400),
				new RouteStopEntity(route152, origin, 20, 300),
				new RouteStopEntity(route152, success, 21, 500),
				new RouteStopEntity(route152, middle, 22, 600),
				new RouteStopEntity(route152, insufficient, 23, 400),
				new RouteStopEntity(route103, origin, 30, 300),
				new RouteStopEntity(route103, success, 31, 500),
				new RouteStopEntity(route103, middle, 32, 600),
				new RouteStopEntity(route103, insufficient, 33, 400),
				new RouteStopEntity(route142, origin, 40, 300),
				new RouteStopEntity(route142, success, 41, 500)
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

}
