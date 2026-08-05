package com.example.backend.domain;

/** FE 표시 계약에 사용하는 버스 차량 유형과 TOPIS 코드의 대응 관계이다. */
public enum BusVehicleType {

	LOW_FLOOR("저상버스"),
	STANDARD("일반버스"),
	ARTICULATED("굴절버스");

	private final String displayName;

	BusVehicleType(String displayName) {
		this.displayName = displayName;
	}

	public String displayName() {
		return displayName;
	}

	public static BusVehicleType fromTopisCode(String code) {
		return switch (code) {
			case "1" -> LOW_FLOOR;
			case "2" -> ARTICULATED;
			default -> STANDARD;
		};
	}
}
