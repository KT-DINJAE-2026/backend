package com.example.backend.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

	private final Api api = new Api();
	private final MasterData masterData = new MasterData();

	public Api getApi() {
		return api;
	}

	public MasterData getMasterData() {
		return masterData;
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
}
