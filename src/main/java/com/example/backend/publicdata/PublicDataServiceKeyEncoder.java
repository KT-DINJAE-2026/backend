package com.example.backend.publicdata;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** 공공데이터포털의 Decoding/Encoding 인증키를 이중 인코딩 없이 쿼리에 넣는다. */
public final class PublicDataServiceKeyEncoder {

	private static final Pattern ENCODED_SERVICE_KEY = Pattern.compile(
			"(?:[A-Za-z0-9._~-]|%[0-9A-Fa-f]{2})+"
	);

	private PublicDataServiceKeyEncoder() {
	}

	public static String encode(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("공공데이터포털 인증키가 비어 있습니다.");
		}
		if (!value.contains("%")) {
			// 포털의 Decoding 키는 '+', '/', '=' 등을 포함할 수 있으므로 쿼리 값으로 한 번 인코딩한다.
			return URLEncoder.encode(value, StandardCharsets.UTF_8);
		}
		// Encoding 키의 %2B 등을 다시 인코딩하면 %252B가 되어 인증에 실패한다.
		if (!ENCODED_SERVICE_KEY.matcher(value).matches()) {
			throw new IllegalArgumentException("Encoding 인증키 형식이 올바르지 않습니다.");
		}
		return value;
	}
}
