package com.example.backend.demo;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

/**
 * FE가 외부 시스템 없이 API 계약과 여러 QR 진입을 시험하도록 demo 프로필의 H2 데이터를 구성한다.
 *
 * <p>정류장 ID·ARS 번호·명칭·좌표와 노선 경유 순서는 서울시가 공개한
 * 2026-08-04 정류소 위치 및 노선별 정류소 자료를 사용한다. 도착시간·차량·혼잡도는
 * {@code JourneyTestDataService}가 만드는 시연값이며 실제 운행 결과가 아니다.</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.demo", name = "enabled", havingValue = "true")
public class DemoDataInitializer implements ApplicationRunner {

	private static final String SEOUL_OPERATOR_CODE = "11100";

	private static final List<DemoStop> STOPS = List.of(
			new DemoStop(
					"107000007", "08007", "돈암사거리.성신여대입구", "성북구",
					"37.5937432794", "127.0181313708"
			),
			new DemoStop(
					"107000085", "08175", "삼선동주민센터", "성북구",
					"37.5906107149", "127.0143473137"
			),
			new DemoStop(
					"107000087", "08177", "성북구청.성북경찰서", "성북구",
					"37.5881520000", "127.0174320000"
			),
			new DemoStop(
					"107000089", "08179", "보문역2번출구", "성북구",
					"37.5858514183", "127.0189209428"
			),
			new DemoStop(
					"107000091", "08181", "보문동성당", "성북구",
					"37.5823963244", "127.0207962235"
			),
			new DemoStop(
					"107000093", "08183", "보문동주민센터", "성북구",
					"37.5804514958", "127.0218634882"
			),
			new DemoStop(
					"100000147", "01243", "신설동역오거리", "종로구",
					"37.5756947252", "127.0228414296"
			),
			// FE 오류 화면 계약을 유지하기 위한 격리된 테스트 정류장이다.
			new DemoStop(
					"121009999", "22999", "시연용 직통 노선 없음", "성북구",
					"37.5845800000", "127.0341400000"
			)
	);

	private static final List<DemoRoute> ROUTES = List.of(
			new DemoRoute(
					"100100129", "1014", "성북생태체험관", "동묘앞역",
					List.of(
							new DemoRouteStop("107000087", 14),
							new DemoRouteStop("107000089", 15),
							new DemoRouteStop("107000091", 16),
							new DemoRouteStop("107000093", 17),
							new DemoRouteStop("100000147", 18)
					)
			),
			new DemoRoute(
					"100100008", "103", "삼화상운", "서울역",
					List.of(
							new DemoRouteStop("107000007", 18),
							new DemoRouteStop("107000085", 19),
							new DemoRouteStop("107000087", 20),
							new DemoRouteStop("107000089", 21),
							new DemoRouteStop("107000091", 22),
							new DemoRouteStop("107000093", 23),
							new DemoRouteStop("100000147", 24)
					)
			),
			new DemoRoute(
					"100100021", "142", "도봉산입구", "방배동",
					List.of(
							new DemoRouteStop("107000007", 22),
							new DemoRouteStop("107000085", 23),
							new DemoRouteStop("107000087", 24),
							new DemoRouteStop("107000089", 25)
					)
			),
			new DemoRoute(
					"100100031", "152", "혜화여고.수유중학교입구", "경인교육대후문",
					List.of(
							new DemoRouteStop("107000007", 14),
							new DemoRouteStop("107000085", 15),
							new DemoRouteStop("107000087", 16),
							new DemoRouteStop("107000089", 17),
							new DemoRouteStop("107000091", 18),
							new DemoRouteStop("107000093", 19),
							new DemoRouteStop("100000147", 20)
					)
			)
	);

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
		Map<String, StopEntity> stopsById = new LinkedHashMap<>();
		for (DemoStop stop : STOPS) {
			stopsById.put(stop.id(), saveStop(stop));
		}

		for (DemoRoute routeData : ROUTES) {
			RouteEntity route = saveRoute(routeData);
			List<RouteStopEntity> routeStops = routeData.stops().stream()
					.map(routeStop -> new RouteStopEntity(
							route,
							requiredStop(stopsById, routeStop.stopId()),
							routeStop.stopOrder(),
							null
					))
					.toList();
			routeStopRepository.saveAll(routeStops);
		}
	}

	private StopEntity saveStop(DemoStop stop) {
		return stopRepository.save(new StopEntity(
				stop.id(),
				SEOUL_OPERATOR_CODE,
				"DEMO-" + stop.id(),
				stop.arsId(),
				stop.name(),
				stop.districtName(),
				new BigDecimal(stop.latitude()),
				new BigDecimal(stop.longitude())
		));
	}

	private RouteEntity saveRoute(DemoRoute route) {
		return routeRepository.save(new RouteEntity(
				route.id(),
				SEOUL_OPERATOR_CODE,
				"DEMO-" + route.id(),
				route.number(),
				"B",
				route.startStopName(),
				route.endStopName(),
				"115"
		));
	}

	private StopEntity requiredStop(Map<String, StopEntity> stopsById, String stopId) {
		StopEntity stop = stopsById.get(stopId);
		if (stop == null) {
			throw new IllegalStateException("demo 노선에 등록되지 않은 정류장이 있습니다: " + stopId);
		}
		return stop;
	}

	private record DemoStop(
			String id,
			String arsId,
			String name,
			String districtName,
			String latitude,
			String longitude
	) {
	}

	private record DemoRoute(
			String id,
			String number,
			String startStopName,
			String endStopName,
			List<DemoRouteStop> stops
	) {
	}

	private record DemoRouteStop(String stopId, int stopOrder) {
	}
}
