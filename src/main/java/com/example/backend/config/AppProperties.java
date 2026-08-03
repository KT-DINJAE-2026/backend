package com.example.backend.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

	private final Api api = new Api();
	private final MasterData masterData = new MasterData();
	private final Topis topis = new Topis();
	private final Demo demo = new Demo();

	public Api getApi() {
		return api;
	}

	public MasterData getMasterData() {
		return masterData;
	}

	public Topis getTopis() {
		return topis;
	}

	public Demo getDemo() {
		return demo;
	}

	public static class Api {

		private List<String> initialDestinationStopIds = new ArrayList<>();

		public List<String> getInitialDestinationStopIds() {
			return initialDestinationStopIds;
		}

		public void setInitialDestinationStopIds(List<String> initialDestinationStopIds) {
			this.initialDestinationStopIds = initialDestinationStopIds;
		}
	}

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

	public static class Demo {

		private boolean enabled;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}
	}
}
