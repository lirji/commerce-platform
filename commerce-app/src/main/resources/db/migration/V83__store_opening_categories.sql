CREATE TABLE store_opening_category (
  category_code VARCHAR(64) NOT NULL COMMENT '平台经营类目的稳定代码',
  parent_code VARCHAR(64) NULL COMMENT '一级类目的代码，一级本身为空',
  name VARCHAR(56) NOT NULL COMMENT '类目显示名称',
  sort_order SMALLINT UNSIGNED NOT NULL COMMENT '同级展示顺序',
  enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否允许在开店流程选择',
  PRIMARY KEY (category_code),
  KEY ix_opening_category_parent (parent_code, sort_order),
  CONSTRAINT fk_opening_category_parent FOREIGN KEY (parent_code) REFERENCES store_opening_category(category_code),
  CONSTRAINT ck_opening_category_parent CHECK (parent_code IS NULL OR parent_code <> category_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='开店使用的两级平台经营类目字典';

INSERT INTO store_opening_category(category_code,parent_code,name,sort_order) VALUES
  ('HOME',NULL,'家居百货',10),
  ('FOOD',NULL,'食品饮料',20),
  ('APPAREL',NULL,'服饰鞋包',30),
  ('BEAUTY',NULL,'美妆个护',40),
  ('ELECTRONICS',NULL,'数码家电',50),
  ('FAMILY',NULL,'母婴玩具',60),
  ('SPORTS',NULL,'运动户外',70),
  ('PETS',NULL,'宠物用品',80),
  ('OFFICE',NULL,'文具办公',90);

INSERT INTO store_opening_category(category_code,parent_code,name,sort_order) VALUES
  ('HOME_STORAGE','HOME','家居收纳',10),
  ('HOME_KITCHEN','HOME','厨具餐具',20),
  ('HOME_TEXTILE','HOME','家纺布艺',30),
  ('HOME_CLEANING','HOME','日用清洁',40),
  ('FOOD_SNACKS','FOOD','休闲食品',10),
  ('FOOD_DRINKS','FOOD','冲调饮料',20),
  ('FOOD_GRAINS','FOOD','粮油调味',30),
  ('FOOD_FRESH','FOOD','生鲜食品',40),
  ('APPAREL_CLOTHES','APPAREL','服装',10),
  ('APPAREL_SHOES','APPAREL','鞋靴',20),
  ('APPAREL_BAGS','APPAREL','箱包配饰',30),
  ('BEAUTY_SKIN','BEAUTY','护肤彩妆',10),
  ('BEAUTY_CARE','BEAUTY','个人护理',20),
  ('BEAUTY_HAIR','BEAUTY','洗护造型',30),
  ('ELECTRONICS_MOBILE','ELECTRONICS','手机与配件',10),
  ('ELECTRONICS_COMPUTER','ELECTRONICS','电脑与配件',20),
  ('ELECTRONICS_APPLIANCES','ELECTRONICS','生活家电',30),
  ('FAMILY_BABY','FAMILY','母婴用品',10),
  ('FAMILY_KIDS','FAMILY','童装童鞋',20),
  ('FAMILY_TOYS','FAMILY','玩具益智',30),
  ('SPORTS_FITNESS','SPORTS','运动健身',10),
  ('SPORTS_OUTDOOR','SPORTS','户外装备',20),
  ('SPORTS_CYCLING','SPORTS','骑行装备',30),
  ('PETS_FOOD','PETS','宠物食品',10),
  ('PETS_GEAR','PETS','宠物日用',20),
  ('PETS_CARE','PETS','宠物护理',30),
  ('OFFICE_STATIONERY','OFFICE','文具耗材',10),
  ('OFFICE_EQUIPMENT','OFFICE','办公设备',20);

ALTER TABLE store_record
  ADD COLUMN opening_category_code VARCHAR(64) NULL COMMENT '开店选择的类目代码，旧手填资料为空',
  ADD CONSTRAINT fk_store_opening_category FOREIGN KEY (opening_category_code)
    REFERENCES store_opening_category(category_code);
