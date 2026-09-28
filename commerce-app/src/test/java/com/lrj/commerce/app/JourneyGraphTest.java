package com.lrj.commerce.app;

import com.lrj.commerce.journey.api.JourneyApi.*;
import com.lrj.commerce.journey.domain.JourneyGraph;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 以无效配置和相同分支输入验证安全边界，不复制遍历实现来制造通过。 */
class JourneyGraphTest {

	private Definition graph(String entry, Node... nodes) {
		return new Definition("graph", 1, "store", "图", Trigger.MANUAL, Instant.EPOCH,
				Instant.EPOCH.plusSeconds(3600), 60, entry, List.of(nodes));
	}

	private Node end(String id) {
		return new Node(id, Kind.END, null, null, null, null, null, null, null, null);
	}

	private Node waitFor(String id, int seconds, String next) {
		return new Node(id, Kind.WAIT, seconds, next, null, null, null, null, null, null);
	}

	private void rejected(ValidationCode code, Definition definition) {
		var result = JourneyGraph.validate(definition);
		assertFalse(result.valid());
		assertEquals(code, result.issues().getFirst().code());
		assertThrows(com.lrj.commerce.kernel.DomainException.class, () -> JourneyGraph.requireValid(definition));
	}

	@Test
	void boundedReachableGraphIsValidAndEntryIsRequired() {
		assertTrue(JourneyGraph.validate(graph("wait", waitFor("wait", 1, "end"), end("end"))).valid());
		rejected(ValidationCode.MISSING_START_NODE, graph("missing", end("end")));
	}

	@Test
	void danglingEdgesOrphansAndDuplicateNodesAreRejected() {
		rejected(ValidationCode.MISSING_TARGET, graph("wait", waitFor("wait", 1, "missing")));
		rejected(ValidationCode.UNREACHABLE_NODE, graph("end", end("end"), end("orphan")));
		rejected(ValidationCode.DUPLICATE_NODE, graph("end", end("end"), end("end")));
	}

	@Test
	void cyclesAndUnboundedOrMissingWaitCannotPublish() {
		rejected(ValidationCode.CYCLE_NOT_SUPPORTED, graph("wait", waitFor("wait", 1, "wait")));
		rejected(ValidationCode.INVALID_WAIT, graph("wait", waitFor("wait", 604801, "end"), end("end")));
		rejected(ValidationCode.INVALID_WAIT, graph("wait", waitFor("wait", 0, "end"), end("end")));
	}

	@Test
	void typedActionConfigurationCannotBeSilentlyIgnored() {
		rejected(ValidationCode.UNSUPPORTED_ACTION,
				graph("grant", new Node("grant", Kind.GRANT, null, "end", null, null, null, null, null, null), end("end")));
		rejected(ValidationCode.UNKNOWN_NODE_TYPE,
				graph("unknown", new Node("unknown", null, null, null, null, null, null, null, null, null)));
	}
}
