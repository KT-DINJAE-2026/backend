package com.example.backend.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code application*.yaml}의 {@code app.*} 사용자 설정을 타입으로 묶는다.
 *
 * <p>프로필별 설정과 환경 변수는 이 객체를 통해 서비스에 전달한다. 새 설정을 추가할 때는
 * README의 설정 표와 각 프로필 YAML도 함께 갱신한다.</p>
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

	private final Api api = new Api();
	private final MasterData masterData = new MasterData();
	private final Topis topis = new Topis();
	private final Holiday holiday = new Holiday();
	private final Weather weather = new Weather();
	private final Headway headway = new Headway();
	private final Model model = new Model();
	private final Demo demo = new Demo();
	private final Cors cors = new Cors();

	public Api getApi() {
		return api;
	}

	public MasterData getMasterData() {
		return masterData;
	}

	public Topis getTopis() {
		return topis;
	}

	public Holiday getHoliday() {
		return holiday;
	}

	public Weather getWeather() {
		return weather;
	}

	public Headway getHeadway() {
		return headway;
	}

	public Model getModel() {
		return model;
	}

	public Demo getDemo() {
		return demo;
	}

	public Cors getCors() {
		return cors;
	}

	/** QR 진입 직후 보여줄 초기 목적지 목록 등 API 동작 설정. */
	public static class Api {

		private List<String> initialDestinationStopIds = new ArrayList<>();

		public List<String> getInitialDestinationStopIds() {
			return initialDestinationStopIds;
		}

		public void setInitialDestinationStopIds(List<String> initialDestinationStopIds) {
			this.initialDestinationStopIds = initialDestinationStopIds;
		}
	}

	/** 국토교통부 기반정보 파일 적재 범위와 파일 경로 설정. */
	public static class MasterData {

		private boolean importEnabled;
		private String cityName = "서울특별시";
		private String stopFile = "";
		private String routeFile = "";
		private String routeStopFile = "";

		public boolean isImportEnabled() {
			return importEnabled;
		}

		public void setImportEnabled(boolean importEnabled) {
			this.importEnabled = importEnabled;
		}

		public String getCityName() {
			return cityName;
		}

		public void setCityName(String cityName) {
			this.cityName = cityName;
		}

		public String getStopFile() {
			return stopFile;
		}

		public void setStopFile(String stopFile) {
			this.stopFile = stopFile;
		}

		public String getRouteFile() {
			return routeFile;
		}

		public void setRouteFile(String routeFile) {
			this.routeFile = routeFile;
		}

		public String getRouteStopFile() {
			return routeStopFile;
		}

		public void setRouteStopFile(String routeStopFile) {
			this.routeStopFile = routeStopFile;
		}
	}

	/** 서울시 버스 도착정보 API의 인증·제한시간·캐시 설정. */
	public static class Topis {

		private boolean enabled = true;
		private String baseUrl = "http://ws.bus.go.kr/api/rest";
		private String serviceKey = "";
		private Duration connectTimeout = Duration.ofSeconds(3);
		private Duration requestTimeout = Duration.ofSeconds(5);
		private Duration cacheTtl = Duration.ofSeconds(20);

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getBaseUrl() {
			return baseUrl;
		}

		public void setBaseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
		}

		public String getServiceKey() {
			return serviceKey;
		}

		public void setServiceKey(String serviceKey) {
			this.serviceKey = serviceKey;
		}

		public Duration getConnectTimeout() {
			return connectTimeout;
		}

		public void setConnectTimeout(Duration connectTimeout) {
			this.connectTimeout = connectTimeout;
		}

		public Duration getRequestTimeout() {
			return requestTimeout;
		}

		public void setRequestTimeout(Duration requestTimeout) {
			this.requestTimeout = requestTimeout;
		}

		public Duration getCacheTtl() {
			return cacheTtl;
		}

		public void setCacheTtl(Duration cacheTtl) {
			this.cacheTtl = cacheTtl;
		}
	}

	/** 외부 시스템 없이 FE 계약을 검증하는 demo 데이터 활성화 설정. */
	public static class Demo {

		private boolean enabled;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}
	}

	/** 한국천문연구원 공휴일 API의 인증·제한시간·연도 캐시 설정. */
	public static class Holiday {

		private boolean enabled = true;
		private String baseUrl = "https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService";
		private String serviceKey = "";
		private Duration connectTimeout = Duration.ofSeconds(3);
		private Duration requestTimeout = Duration.ofSeconds(5);
		private Duration cacheTtl = Duration.ofHours(24);

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getBaseUrl() {
			return baseUrl;
		}

		public void setBaseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
		}

		public String getServiceKey() {
			return serviceKey;
		}

		public void setServiceKey(String serviceKey) {
			this.serviceKey = serviceKey;
		}

		public Duration getConnectTimeout() {
			return connectTimeout;
		}

		public void setConnectTimeout(Duration connectTimeout) {
			this.connectTimeout = connectTimeout;
		}

		public Duration getRequestTimeout() {
			return requestTimeout;
		}

		public void setRequestTimeout(Duration requestTimeout) {
			this.requestTimeout = requestTimeout;
		}

		public Duration getCacheTtl() {
			return cacheTtl;
		}

		public void setCacheTtl(Duration cacheTtl) {
			this.cacheTtl = cacheTtl;
		}
	}

	/** Open-Meteo 시간별 예보의 제한시간과 일별 캐시 설정. */
	public static class Weather {

		private boolean enabled = true;
		private String baseUrl = "https://api.open-meteo.com/v1/forecast";
		private Duration connectTimeout = Duration.ofSeconds(3);
		private Duration requestTimeout = Duration.ofSeconds(5);
		private Duration cacheTtl = Duration.ofMinutes(15);

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getBaseUrl() {
			return baseUrl;
		}

		public void setBaseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
		}

		public Duration getConnectTimeout() {
			return connectTimeout;
		}

		public void setConnectTimeout(Duration connectTimeout) {
			this.connectTimeout = connectTimeout;
		}

		public Duration getRequestTimeout() {
			return requestTimeout;
		}

		public void setRequestTimeout(Duration requestTimeout) {
			this.requestTimeout = requestTimeout;
		}

		public Duration getCacheTtl() {
			return cacheTtl;
		}

		public void setCacheTtl(Duration cacheTtl) {
			this.cacheTtl = cacheTtl;
		}
	}

	/** 서울시 노선 기본정보에서 추출한 요일 유형별 계획 배차간격 설정. */
	public static class Headway {

		private boolean enabled = true;
		private String scheduleResource = "classpath:headway/seoul-bus-headway-20260804.csv";

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getScheduleResource() {
			return scheduleResource;
		}

		public void setScheduleResource(String scheduleResource) {
			this.scheduleResource = scheduleResource;
		}
	}

	/**
	 * AI팀이 전달한 입석 예측 PMML 모델의 위치와 활성화 설정.
	 *
	 * <p>모델 파일은 용량이 커서 저장소에 커밋하지 않으므로(`models/README.md` 참고) 파일이 없는
	 * 환경에서는 {@code enabled}를 꺼야 한다. demo 프로필과 테스트는 기본으로 꺼져 있다.</p>
	 */
	public static class Model {

		private boolean enabled = true;
		private String directory = "models";
		private String classifierFile = "model_a.pmml";
		private String regressorFile = "model_b.pmml";

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getDirectory() {
			return directory;
		}

		public void setDirectory(String directory) {
			this.directory = directory;
		}

		public String getClassifierFile() {
			return classifierFile;
		}

		public void setClassifierFile(String classifierFile) {
			this.classifierFile = classifierFile;
		}

		public String getRegressorFile() {
			return regressorFile;
		}

		public void setRegressorFile(String regressorFile) {
			this.regressorFile = regressorFile;
		}
	}

	/** FE가 API에 접근할 수 있는 브라우저 Origin 목록. */
	public static class Cors {

		private List<String> allowedOrigins = new ArrayList<>(List.of(
				"http://localhost:5173",
				"https://kd-dinjae-2026-fe.vercel.app"
		));

		public List<String> getAllowedOrigins() {
			return allowedOrigins;
		}

		public void setAllowedOrigins(List<String> allowedOrigins) {
			this.allowedOrigins = allowedOrigins;
		}
	}
}
