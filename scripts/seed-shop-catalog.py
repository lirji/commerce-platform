#!/usr/bin/env python3
"""为本地验收店铺补齐类目与商品展示资料；只写项目本地库，不改成交价。"""

from pathlib import Path
import hashlib, json, os, shlex, subprocess

root = Path(__file__).resolve().parents[1]
env = {}
for line in (root / ".local/runtime.env").read_text().splitlines():
    if not line.strip() or line.lstrip().startswith("#"):
        continue
    key, value = line.removeprefix("export ").split("=", 1)
    env[key] = shlex.split(value)[0]
schema = os.getenv("COMMERCE_E2E_SCHEMA", "commerce_local")
if schema not in ("commerce_local", "commerce_test_20260923"):
    raise SystemExit("Only project local/test schemas are allowed.")
target = os.getenv("COMMERCE_SHOP_SEED_TARGET", "e2e")
if target == "demo":
    tenant, store, existing = (
        "demo",
        "store-demo",
        {
            "coffee": "sku-coffee",
            "mug": "sku-mug",
            "bag": "sku-bag",
        },
    )
elif target == "e2e":
    access = json.loads((root / ".local/e2e-access.json").read_text())
    tenant = access["tenant"]
    store = access.get("storeId", "browser-store")
    existing = {"coffee": "coffee", "mug": "mug", "bag": "bag"}
    if not tenant.startswith("browser-"):
        raise SystemExit(
            "e2e catalog seed only targets the local browser fixture tenant."
        )
else:
    raise SystemExit("COMMERCE_SHOP_SEED_TARGET must be e2e or demo.")


def spec_json(pairs):
    items = [{"name": name, "value": value} for name, value in sorted(pairs)]
    return json.dumps(items, ensure_ascii=False, separators=(",", ":"))


def spec_key(pairs):
    return hashlib.sha256(spec_json(pairs).encode()).hexdigest()


def q(value):
    return "'" + str(value).replace("\\", "\\\\").replace("'", "''") + "'"


categories = [
    ("drinks", None, "饮品", 1),
    ("coffee-cat", "drinks", "咖啡", 2),
    ("tea-cat", "drinks", "茶饮", 2),
    ("living", None, "生活好物", 1),
    ("tableware", "living", "餐具", 2),
    ("outing", "living", "出行", 2),
    ("stationery", "living", "文具", 2),
]
products = [
    (
        "coffee-box",
        "精品咖啡礼盒",
        "咖啡",
        "日常焙火",
        "coffee-cat",
        "中度烘焙拼配豆，适合手冲与分享。会员优惠在结算报价时确认。",
        "包装",
        "礼盒",
        "coffee",
        "精品咖啡礼盒",
        "129.00",
        "DAILY-COFFEE-01",
    ),
    (
        "ceramic-mug",
        "陶瓷随行杯",
        "餐具",
        "日常器皿",
        "tableware",
        "350ml 陶瓷随行杯，杯盖可密封。适合通勤与办公室。",
        "容量",
        "350ml",
        "mug",
        "陶瓷随行杯",
        "59.00",
        "DAILY-MUG-01",
    ),
    (
        "commute-bag",
        "城市通勤包",
        "出行",
        "日常出行",
        "outing",
        "13 寸隔层通勤包，轻量防泼水。适合日常往返。",
        "尺寸",
        "13寸",
        "bag",
        "城市通勤包",
        "239.00",
        "DAILY-BAG-01",
    ),
    (
        "tea-box",
        "日式煎茶礼盒",
        "茶饮",
        "日常茶事",
        "tea-cat",
        "单丛煎茶礼盒，清香耐泡。适合下午与待客。",
        "包装",
        "礼盒",
        "tea",
        "日式煎茶礼盒",
        "89.00",
        "DAILY-TEA-01",
    ),
    (
        "pour-kettle",
        "手冲细口壶",
        "餐具",
        "日常器皿",
        "tableware",
        "不锈钢细口手冲壶，出水稳定。适合家用冲煮。",
        "容量",
        "600ml",
        "kettle",
        "手冲细口壶",
        "168.00",
        "DAILY-KETTLE-01",
    ),
    (
        "soft-notebook",
        "软皮手账本",
        "文具",
        "日常书写",
        "stationery",
        "A5 软皮手账，内页点阵。适合记录与计划。",
        "规格",
        "A5",
        "notebook",
        "软皮手账本",
        "45.00",
        "DAILY-NOTE-01",
    ),
]
image = [{"url": "/media/coffee.svg", "alt": "商品示意图"}]
images_json = json.dumps(image, ensure_ascii=False, separators=(",", ":"))
sql = ["SET NAMES utf8mb4;"]
for category_id, parent_id, name, depth in categories:
    sql.append(
        "INSERT INTO catalog_category(tenant_id,store_id,category_id,parent_id,name,depth,status,version) VALUES("
        f"{q(tenant)},{q(store)},{q(category_id)},{q(parent_id) if parent_id else 'NULL'},{q(name)},{depth},'ACTIVE',0) "
        "ON DUPLICATE KEY UPDATE name=VALUES(name),status='ACTIVE';"
    )
for (
    product_id,
    title,
    category,
    brand,
    category_id,
    description,
    spec_name,
    spec_value,
    sku_id,
    sku_title,
    price,
    barcode,
) in products:
    specs = [(spec_name, spec_value)]
    sql.append(
        "INSERT INTO catalog_product(tenant_id,product_id,store_id,title,category,brand,version) VALUES("
        f"{q(tenant)},{q(product_id)},{q(store)},{q(title)},{q(category)},{q(brand)},0) "
        "ON DUPLICATE KEY UPDATE title=VALUES(title),category=VALUES(category),brand=VALUES(brand);"
    )
    sql.append(
        "INSERT INTO catalog_product_profile(tenant_id,store_id,product_id,category_id,description,images_json,version) VALUES("
        f"{q(tenant)},{q(store)},{q(product_id)},{q(category_id)},{q(description)},{q(images_json)},1) "
        "ON DUPLICATE KEY UPDATE category_id=VALUES(category_id),description=VALUES(description),images_json=VALUES(images_json);"
    )
    sku_id = existing.get(sku_id, sku_id)
    if sku_id in existing.values():
        sql.append(
            "UPDATE catalog_sku SET product_id="
            f"{q(product_id)},specifications_json={q(spec_json(specs))},specification_key={q(spec_key(specs))} "
            f"WHERE tenant_id={q(tenant)} AND store_id={q(store)} AND sku_id={q(sku_id)} AND product_id IS NULL;"
        )
    else:
        sql.append(
            "INSERT INTO catalog_sku(tenant_id,sku_id,store_id,title,unit_price,revision,status,product_id,specifications_json,specification_key) VALUES("
            f"{q(tenant)},{q(sku_id)},{q(store)},{q(sku_title)},{q(price)},1,'ACTIVE',{q(product_id)},{q(spec_json(specs))},{q(spec_key(specs))}) "
            "ON DUPLICATE KEY UPDATE title=VALUES(title),unit_price=VALUES(unit_price),status='ACTIVE',product_id=VALUES(product_id),"
            "specifications_json=VALUES(specifications_json),specification_key=VALUES(specification_key);"
        )
        sql.append(
            "INSERT IGNORE INTO catalog_revision(tenant_id,sku_id,revision,store_id,title,unit_price,status,reason,actor_id) VALUES("
            f"{q(tenant)},{q(sku_id)},1,{q(store)},{q(sku_title)},{q(price)},'ACTIVE','本地商城演示上架','SYSTEM');"
        )
        sql.append(
            "INSERT INTO inventory_stock(tenant_id,store_id,sku_id,available,held,sold,version) VALUES("
            f"{q(tenant)},{q(store)},{q(sku_id)},80,0,0,0) ON DUPLICATE KEY UPDATE sku_id=sku_id;"
        )
    sql.append(
        "INSERT INTO catalog_sku_barcode(tenant_id,store_id,sku_id,barcode,version) VALUES("
        f"{q(tenant)},{q(store)},{q(sku_id)},{q(barcode)},1) "
        "ON DUPLICATE KEY UPDATE barcode=VALUES(barcode);"
    )
process = subprocess.run(
    [
        "docker",
        "exec",
        "-i",
        "-e",
        "MYSQL_PWD",
        os.getenv("COMMERCE_MYSQL_CONTAINER", "dev-infra-mysql84-1"),
        "mysql",
        "--default-character-set=utf8mb4",
        "-ucommerce_app",
        schema,
    ],
    input="\n".join(sql) + "\n",
    text=True,
    capture_output=True,
    env=dict(os.environ, MYSQL_PWD=env["COMMERCE_DB_PASSWORD"]),
    timeout=20,
)
if process.returncode:
    raise SystemExit("Shop catalog seed failed; credentials omitted.")
print(
    f"Shop catalog seed persisted for {target} / {store}. Categories=7, products=6, existing SKU titles/prices unchanged."
)
