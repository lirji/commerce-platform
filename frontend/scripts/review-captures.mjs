import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "@playwright/test";

// 仅用于人工视觉复核，将实际截图排入联系表；不修改产品或原始截图。
const root = fileURLToPath(new URL("../../", import.meta.url));
const stage = process.argv[2] ?? "after";
if (!/^[a-z-]+$/.test(stage)) throw new Error("Invalid stage");
const folder = path.join(root, "docs/evidence/frontend-interaction-refresh", stage);
const results = JSON.parse(fs.readFileSync(path.join(folder, "capture.json"), "utf8")).results;
const out = path.join(root, ".local/frontend-interaction-refresh", "review-" + stage);
fs.mkdirSync(out, { recursive: true });
const browser = await chromium.launch();
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1590 } });
  for (let i = 0; i < results.length; i += 6) {
    await page.setContent('<style>body{margin:0;background:#dde2ec;font:14px sans-serif}main{display:grid;grid-template-columns:720px 720px}figure{margin:0;height:530px}figcaption{height:30px;padding:4px;box-sizing:border-box}img{max-width:720px;max-height:500px;object-fit:contain;object-position:top left}</style><main>' + results.slice(i,i+6).map(r => '<figure><figcaption>' + r.file + '</figcaption><img src="data:image/png;base64,' + fs.readFileSync(path.join(folder, r.file)).toString('base64') + '"></figure>').join('') + '</main>');
    await page.evaluate(() => Promise.all([...document.images].map(im => im.decode())));
    await page.screenshot({ path: path.join(out, `sheet-${String(i / 6 + 1).padStart(2, "0")}.png`) });
  }
} finally { await browser.close(); }
console.log(JSON.stringify({ sheets: Math.ceil(results.length / 6), screenshots: results.length, out }));
