package com.lrj.commerce.app.http.marketing.journey;

import com.lrj.commerce.journey.api.JourneyApi;
import com.lrj.commerce.runtime.api.identity.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** HTTP仅转换协议参数，审批、身份和恢复约束由旅程用例校验。 */
@RestController
@RequestMapping("/v1")
public class JourneyController {

	private final JourneyApi journeys;

	public JourneyController(JourneyApi journeys) {
		this.journeys = journeys;
	}

	record Revision(long expectedVersion) {
	}

	/** 不可变版本草稿。 */
	@PostMapping("/admin/journeys")
	public Object create(@AuthenticationPrincipal Actor actor, @RequestHeader("Idempotency-Key") String key,
			@RequestBody JourneyApi.Definition input) {
		return journeys.create(actor, key, input);
	}

	/** 有界读取最新定义。 */
	@GetMapping("/admin/journeys")
	public Object list(@AuthenticationPrincipal Actor actor, @RequestParam(defaultValue = "") String after,
			@RequestParam(defaultValue = "50") int limit) {
		return journeys.definitions(actor, after, limit);
	}

	/** 无副作用校验，机器码用于运营定位无效节点配置。 */
	@PostMapping("/admin/journeys/validate")
	public Object validate(@AuthenticationPrincipal Actor actor, @RequestBody JourneyApi.Definition input) {
		return journeys.validate(actor, input);
	}

	/** 预览不会入组或调用真实发放；等待节点明确标记未来依赖。 */
	@PostMapping("/admin/journeys/{id}/{version}/preview")
	public Object preview(@AuthenticationPrincipal Actor actor, @PathVariable String id, @PathVariable long version,
			@RequestBody JourneyApi.Preview input) {
		return journeys.preview(actor, id, version, input);
	}

	/** 明确动作和预期版本，不能任意指定新状态。 */
	@PostMapping("/admin/journeys/{id}/{version}/{action}")
	public Object change(@AuthenticationPrincipal Actor actor, @RequestHeader("Idempotency-Key") String key,
			@PathVariable String id, @PathVariable long version, @PathVariable String action,
			@RequestBody Revision input) {
		return journeys.change(actor, key, id, version, input.expectedVersion(), action);
	}

	/** 手工入组保留来源去重键。 */
	@PostMapping("/admin/journey-instances")
	public Object enroll(@AuthenticationPrincipal Actor actor, @RequestHeader("Idempotency-Key") String key,
			@RequestBody JourneyApi.Start input) {
		return journeys.enroll(actor, key, input);
	}

	/** 查询范围由服务端认证身份决定。 */
	@GetMapping({ "/admin/journey-instances", "/journey-instances" })
	public Object instances(@AuthenticationPrincipal Actor actor, @RequestParam(defaultValue = "") String after,
			@RequestParam(defaultValue = "50") int limit) {
		return journeys.instances(actor, after, limit);
	}

	/** 只读取固定版本和真实执行记录，不以当前定义补写历史。 */
	@GetMapping({ "/admin/journey-instances/{id}/history", "/journey-instances/{id}/history" })
	public Object history(@AuthenticationPrincipal Actor actor, @PathVariable String id,
			@RequestParam(defaultValue = "-1") long afterVersion, @RequestParam(defaultValue = "50") int limit) {
		return journeys.history(actor, id, afterVersion, limit);
	}

	/** 取消或重试均写命令审计。 */
	@PostMapping("/admin/journey-instances/{id}/{action}")
	public Object control(@AuthenticationPrincipal Actor actor, @RequestHeader("Idempotency-Key") String key,
			@PathVariable String id, @PathVariable String action) {
		return journeys.control(actor, key, id, action);
	}

	/** 运维只推动有限批次。 */
	@PostMapping("/admin/journeys/pump")
	public Object pump(@AuthenticationPrincipal Actor actor) {
		return journeys.pump(actor);
	}

	/** 持久扫描的当前进度与失败状态。 */
	@GetMapping("/admin/journey-scans")
	public Object scans(@AuthenticationPrincipal Actor actor, @RequestParam(defaultValue = "") String after,
			@RequestParam(defaultValue = "50") int limit) {
		return journeys.scans(actor, after, limit);
	}

	/** 隔离恢复保留原检查点，并记录原因。 */
	@PostMapping("/admin/journey-scans/{id}/{version}/retry")
	public Object retryScan(@AuthenticationPrincipal Actor actor, @RequestHeader("Idempotency-Key") String key,
			@PathVariable String id, @PathVariable long version, @RequestBody JourneyApi.ScanRetry input) {
		return journeys.retryScan(actor, key, id, version, input);
	}

	/** 读取本人的真实触达记录。 */
	@GetMapping("/notifications")
	public Object notifications(@AuthenticationPrincipal Actor actor, @RequestParam(defaultValue = "") String after,
			@RequestParam(defaultValue = "50") int limit) {
		return journeys.notifications(actor, after, limit);
	}

}
