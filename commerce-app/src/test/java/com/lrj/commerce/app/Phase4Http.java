package com.lrj.commerce.app;

import com.lrj.commerce.runtime.JsonCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.UUID;

/** 第四阶段HTTP测试辅助：真实Bearer凭据（只存哈希）与JSON调用，与既有测试相同的认证路径。 */
final class Phase4Http {

	record Reply(int status, JsonNode body) {
	}

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

	private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

	private final JdbcTemplate jdbc;

	private final int port;

	Phase4Http(JdbcTemplate jdbc, int port) {
		this.jdbc = jdbc;
		this.port = port;
	}

	String token(String tenant, String actor, String role) {
		String token = UUID.randomUUID() + "-" + UUID.randomUUID();
		jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,?,?,?)",
				JsonCodec.hash(token), tenant, actor, role, java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
		return token;
	}

	Reply call(String method, String path, String token, String key, Object body) throws Exception {
		var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
		if (token != null)
			req.header("Authorization", "Bearer " + token);
		if (key != null)
			req.header("Idempotency-Key", key);
		if (body != null)
			req.header("Content-Type", "application/json");
		req.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofString(JsonCodec.write(body)));
		var reply = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
		return new Reply(reply.statusCode(), reply.body().isBlank() ? json.nullNode() : json.readTree(reply.body()));
	}

	JsonNode ok(String method, String path, String token, String key, Object body) throws Exception {
		var r = call(method, path, token, key, body);
		if (r.status() != 200)
			throw new AssertionError(method + " " + path + " -> " + r.status() + " " + r.body());
		return r.body();
	}

}
