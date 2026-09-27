package com.lrj.commerce.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 固定字段与Map顺序生成命令摘要；集合的业务规范化由各用例负责。 */
public final class JsonCodec {

	private static final JsonMapper JSON = JsonMapper.builder()
		.findAndAddModules()
		.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
		.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
		.build();

	private JsonCodec() {
	}

	public static String write(Object value) {
		return JSON.writeValueAsString(value);
	}

	public static <T> T read(String value, Class<T> type) {
		return JSON.readValue(value, type);
	}

	/** 读取历史快照：pointer处对象缺少后来新增的字段时补迁移约定的默认值，已有值不覆盖，其他不兼容仍按原样失败。 */
	public static <T> T readLegacy(String value, Class<T> type, String pointer, String field, int fallback) {
		var tree = JSON.readTree(value);
		if (tree.at(pointer) instanceof tools.jackson.databind.node.ObjectNode node && !node.has(field)) {
			node.put(field, fallback);
			return JSON.treeToValue(tree, type);
		}
		return JSON.readValue(value, type);
	}

	public static String hash(String value) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (java.security.NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

}
