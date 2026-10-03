import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { candidate, validateDeclaration } from "./catalog-source.mjs";
const bytes = readFileSync(new URL("../src/iam/catalog.json", import.meta.url));
const source = () => JSON.parse(bytes);
test("actual declaration covers existing central pages and retains compatibility entries without grants", () => {
  const result = candidate(bytes, "a".repeat(40), 3);
  assert.equal(
    result.manifest.menus.filter((m) => m.route?.startsWith("/operations/"))
      .length,
    35,
  );
  assert.equal(
    result.manifest.menus.find((m) => m.code === "menu.collaboration.products")
      .label,
    "商品协作",
  );
  assert.ok(result.manifest.menus.some((m) => m.code === "stores"));
  assert.equal(result.auto_grants, false);
  assert.equal(result.auto_roles, false);
});
test("renaming and route changes preserve stable menu identity and page counts may evolve", () => {
  const value = source();
  const menu = value.menus.find((m) => m.code === "menu.operations.dashboard");
  menu.route = "/operations/new-dashboard";
  menu.label = "新总览";
  value.menus.push({
    code: "menu.new",
    parent: "group.overview",
    route: "/operations/new",
    any_of: menu.any_of,
    label: "新页面",
    position: 99,
  });
  assert.equal(
    validateDeclaration(value).menus.find((m) => m.code === menu.code).route,
    "/operations/new-dashboard",
  );
});
test("missing capabilities, parent cycles and conflicting positions fail before export", () => {
  const unknown = source();
  unknown.menus.find((m) => m.route).any_of = ["commerce.unknown"];
  assert.throws(() => validateDeclaration(unknown));
  const cycle = source();
  cycle.menus[0].parent = cycle.menus[0].code;
  assert.throws(() => validateDeclaration(cycle));
  const positions = source();
  const labelled = positions.menus.filter((m) => m.label);
  labelled[1].position = labelled[0].position;
  assert.throws(() => validateDeclaration(positions));
});
test("old capability semantics and non-forward versions cannot be silently replaced", () => {
  const value = source();
  const current = {
    application: value.application,
    manifest_version: 2,
    capabilities: value.capabilities,
  };
  assert.throws(() => candidate(bytes, "a".repeat(40), 2, current));
  const changed = source();
  changed.capabilities[0].risk_level =
    changed.capabilities[0].risk_level === "HIGH" ? "NORMAL" : "HIGH";
  assert.throws(() =>
    candidate(Buffer.from(JSON.stringify(changed)), "a".repeat(40), 3, current),
  );
});
