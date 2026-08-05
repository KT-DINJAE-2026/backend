package com.example.backend.config;

import java.nio.charset.StandardCharsets;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** 정상·오류를 포함한 모든 JSON 응답의 Content-Type charset을 UTF-8 한 곳에서 선언한다. */
@ControllerAdvice
public class JsonUtf8ResponseAdvice implements ResponseBodyAdvice<Object> {

	@Override
	public boolean supports(
			MethodParameter returnType,
			Class<? extends HttpMessageConverter<?>> converterType
	) {
		return true;
	}

	@Override
	public Object beforeBodyWrite(
			Object body,
			MethodParameter returnType,
			MediaType selectedContentType,
			Class<? extends HttpMessageConverter<?>> selectedConverterType,
			ServerHttpRequest request,
			ServerHttpResponse response
	) {
		if (isJson(selectedContentType)) {
			response.getHeaders().setContentType(new MediaType(
					selectedContentType.getType(),
					selectedContentType.getSubtype(),
					StandardCharsets.UTF_8
			));
		}
		return body;
	}

	private boolean isJson(MediaType mediaType) {
		return MediaType.APPLICATION_JSON.isCompatibleWith(mediaType)
				|| mediaType.getSubtype().endsWith("+json");
	}
}
