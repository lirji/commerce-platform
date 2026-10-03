import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { candidate } from "./catalog-source.mjs";

/** 只读取显式Git版本，避免把未提交页面配置冒充已经发布的源码。 */
const root = fileURLToPath(new URL("../../", import.meta.url));
const args = process.argv.slice(2);
const option = (key) => {
  const at = args.indexOf(key);
  return at < 0 ? undefined : args[at + 1];
};
try {
  if (
    args.length % 2 ||
    new Set(args.filter((_, i) => i % 2 === 0)).size !== args.length / 2 ||
    args.some(
      (arg, i) =>
        i % 2 === 0 &&
        !["--version", "--output", "--commit", "--current-manifest"].includes(
          arg,
        ),
    )
  )
    throw new Error("invalid export arguments");
  const revision = option("--commit") ?? "HEAD";
  const commit = execFileSync(
    "git",
    ["rev-parse", "--verify", "--end-of-options", revision + "^{commit}"],
    { cwd: root, encoding: "utf8" },
  ).trim();
  const bytes = execFileSync(
    "git",
    ["show", commit + ":frontend/src/iam/catalog.json"],
    { cwd: root },
  );
  const current = option("--current-manifest")
    ? JSON.parse(readFileSync(resolve(option("--current-manifest")), "utf8"))
    : undefined;
  const result = candidate(bytes, commit, Number(option("--version")), current);
  if (!option("--output")) throw new Error("explicit output required");
  writeFileSync(
    resolve(option("--output")),
    JSON.stringify(result, null, 2) + "\n",
    { mode: 0o600 },
  );
  process.stdout.write(
    `PASS: ${result.manifest.menus.length} menus; source ${commit}; no publication or authorization writes\n`,
  );
} catch (failure) {
  process.stderr.write(`Export failed: ${failure.message}\n`);
  process.exitCode = 1;
}
