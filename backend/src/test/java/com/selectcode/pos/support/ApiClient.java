package com.selectcode.pos.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.jayway.jsonpath.JsonPath;
import com.selectcode.pos.shared.security.Totp;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * A browser-like client for MockMvc: keeps cookies (session + CSRF) between requests and sends the CSRF token on
 * state-changing calls, like the Next.js app.
 */
public class ApiClient {

	private final MockMvc mvc;

	private final Map<String, Cookie> cookies = new LinkedHashMap<>();

	private String csrfToken;

	public ApiClient(MockMvc mvc) {
		this.mvc = mvc;
	}

	public ResultActions get(String url) throws Exception {
		return send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url), false);
	}

	public ResultActions post(String url, String json) throws Exception {
		return send(withJson(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url), json), true);
	}

	/** POST with an Idempotency-Key header (payments, bill runs, reminders). */
	public ResultActions postWithKey(String url, String json, String idempotencyKey) throws Exception {
		var builder = withJson(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url), json);
		if (idempotencyKey != null) {
			builder.header("Idempotency-Key", idempotencyKey);
		}
		return send(builder, true);
	}

	/** POST with extra headers given as name/value pairs (approvals, idempotency keys). */
	public ResultActions postWithHeaders(String url, String json, String... headers) throws Exception {
		var builder = withJson(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url), json);
		for (int i = 0; i < headers.length; i += 2) {
			if (headers[i + 1] != null) {
				builder.header(headers[i], headers[i + 1]);
			}
		}
		return send(builder, true);
	}

	public ResultActions post(String url) throws Exception {
		return post(url, null);
	}

	public ResultActions put(String url, String json) throws Exception {
		return send(withJson(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url), json), true);
	}

	public ResultActions patch(String url, String json) throws Exception {
		return send(withJson(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(url), json),
				true);
	}

	public ResultActions delete(String url) throws Exception {
		return send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url), true);
	}

	public ResultActions upload(String url, String purpose, String filename, byte[] content) throws Exception {
		var builder = multipart(url).file(new MockMultipartFile("file", filename, "application/octet-stream", content));
		if (purpose != null) {
			builder.param("purpose", purpose);
		}
		return send(builder, true);
	}

	/** Multipart upload of a file with extra request parameters. */
	public ResultActions uploadWith(String url, String filename, byte[] content, String... params) throws Exception {
		var builder = multipart(url).file(new MockMultipartFile("file", filename, "application/octet-stream", content));
		for (int i = 0; i < params.length; i += 2) {
			builder.param(params[i], params[i + 1]);
		}
		return send(builder, true);
	}

	public ResultActions login(String shopCode, String username, String password) throws Exception {
		return post("/api/v1/auth/login", """
				{"shopCode":"%s","username":"%s","password":"%s"}""".formatted(shopCode, username, password));
	}

	public ResultActions platformLogin(String email, String password, String totpSecret) throws Exception {
		String code = totpSecret == null ? "" : Totp.codeAt(totpSecret, Instant.now());
		return post("/api/v1/platform/auth/login", """
				{"email":"%s","password":"%s","totpCode":"%s"}""".formatted(email, password, code));
	}

	public static <T> T json(ResultActions result, String path) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
	}

	public void forgetCookies() {
		cookies.clear();
		csrfToken = null;
	}

	public Cookie sessionCookie() {
		return cookies.get("POS_SESSION");
	}

	public void useSessionCookie(Cookie cookie) {
		cookies.put("POS_SESSION", cookie);
	}

	private ResultActions send(AbstractMockHttpServletRequestBuilder<?> builder, boolean unsafe) throws Exception {
		if (unsafe) {
			ensureCsrf();
			builder.header("X-XSRF-TOKEN", csrfToken);
		}
		if (!cookies.isEmpty()) {
			builder.cookie(cookies.values().toArray(new Cookie[0]));
		}
		ResultActions result = mvc.perform(builder);
		remember(result.andReturn());
		return result;
	}

	private void ensureCsrf() throws Exception {
		if (csrfToken != null) {
			return;
		}
		var builder = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/csrf");
		if (!cookies.isEmpty()) {
			builder.cookie(cookies.values().toArray(new Cookie[0]));
		}
		MvcResult result = mvc.perform(builder).andReturn();
		remember(result);
		csrfToken = JsonPath.read(result.getResponse().getContentAsString(), "$.token");
	}

	private void remember(MvcResult result) {
		for (Cookie cookie : result.getResponse().getCookies()) {
			if (cookie.getMaxAge() == 0) {
				cookies.remove(cookie.getName());
				if ("XSRF-TOKEN".equals(cookie.getName())) {
					csrfToken = null;
				}
			}
			else {
				cookies.put(cookie.getName(), cookie);
			}
		}
	}

	private static AbstractMockHttpServletRequestBuilder<?> withJson(AbstractMockHttpServletRequestBuilder<?> builder, String json) {
		if (json != null) {
			builder.contentType(MediaType.APPLICATION_JSON).content(json);
		}
		return builder;
	}
}
