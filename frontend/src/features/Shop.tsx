import { RecordModal } from "../shared/interactions";
import {
  Alert,
  Button,
  Col,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Modal,
  Row,
  Select,
  Skeleton,
  Space,
  Spin,
  Steps,
  Tag,
  Typography,
} from "antd";
import { useEffect, useState } from "react";
import {
  CatalogFilters,
  type CatalogItem,
  type Category,
} from "./CatalogMerchandising";
import type { Coupon, Order, Quote } from "../shared/contracts";
import { encode, post, useCommand, useResource } from "../shared/api";
import { Blank, ErrorNotice, Status, money } from "../shared/ui";
import { Icon } from "../shared/Icon";
import { PagerActions } from "../shared/interactions";

export function Shop({
  store,
  onOrder,
}: {
  store: string;
  onOrder: () => void;
}) {
  const [filters, setFilters] = useState("");
  const [after, setAfter] = useState("");
  const products = useResource<CatalogItem[]>(
    store
      ? `/catalog/search?storeId=${encode(store)}&after=${encode(after)}&${filters}`
      : null,
  );
  const categories = useResource<Category[]>(
    store ? `/catalog/categories?storeId=${encode(store)}` : null,
  );
  const [titles, setTitles] = useState<Record<string, string>>({});
  const coupons = useResource<Coupon[]>("/coupons");
  const [info, setInfo] = useState<CatalogItem>();
  const [buyQty, setBuyQty] = useState(1);
  const details = useResource<CatalogItem>(
    info
      ? `/catalog/items/${encode(info.skuId)}?storeId=${encode(store)}`
      : null,
  );
  const [signalError, setSignalError] = useState<Error>();
  const signal = (kind: "BROWSE" | "ADD_TO_CART", skuId: string) => {
    const eventId = crypto.randomUUID();
    // 交互记录失败不会丢掉购物袋，错误可见但不改变购物决策。
    post(
      "/members/me/behavior/events",
      { eventId, kind, storeId: store, skuId },
      eventId,
    )
      .then(() => setSignalError(undefined))
      .catch((e) =>
        setSignalError(
          e instanceof Error ? e : new Error("暂时无法保存互动记录"),
        ),
      );
  };
  const [basket, setBasket] = useState<Record<string, number>>({});
  const pointWallet = useResource<{ available: number }>("/members/me/points");
  const [redeemPoints, setRedeemPoints] = useState(0);
  const [coupon, setCoupon] = useState<string>();
  const [quote, setQuote] = useState<Quote>();
  const [bag, setBag] = useState(false);
  const command = useCommand();
  const place = useCommand();
  const [form] = Form.useForm();
  const count = Object.values(basket).reduce((a, b) => a + b, 0);
  const selectedCategory = new URLSearchParams(filters).get("categoryId") ?? "";
  const browseCategories = (categories.data ?? []).filter(
    (c) => c.status !== "RETIRED",
  );
  const setQuantity = (id: string, n: number) => {
    setBasket((b) => ({ ...b, [id]: n }));
    setQuote(undefined);
  };
  const rememberTitle = (sku: CatalogItem) =>
    setTitles((old) => ({ ...old, [sku.skuId]: sku.title }));
  const addToBag = (sku: CatalogItem, quantity = 1) => {
    rememberTitle(sku);
    setQuantity(sku.skuId, (basket[sku.skuId] ?? 0) + quantity);
    signal("ADD_TO_CART", sku.skuId);
  };
  const openProduct = (sku: CatalogItem) => {
    setInfo(sku);
    setBuyQty(1);
    signal("BROWSE", sku.skuId);
  };
  const createQuote = async () => {
    const result = await command.run<Quote>("/quotes", {
      storeId: store,
      items: Object.entries(basket)
        .filter(([, q]) => q > 0)
        .map(([skuId, quantity]) => ({ skuId, quantity })),
      ...(coupon ? { couponId: coupon } : {}),
      ...(redeemPoints > 0 ? { redeemPoints } : {}),
    });
    if (result) setQuote(result);
  };
  useEffect(() => {
    setBuyQty(1);
  }, [info?.skuId]);
  const shown = details.data ?? info;
  const checkoutStep = !count ? 0 : quote ? 2 : 1;

  return (
    <div className="shop-page">
      <div className="shop-masthead">
        <div>
          <span className="shop-kicker">日常好物 · 当前店铺</span>
          <h1>店铺商品</h1>
          <p className="muted">慢慢挑选，优惠券与积分可在结算时使用。</p>
        </div>
        <Button
          type="primary"
          icon={<Icon name="bag" />}
          onClick={() => setBag(true)}
        >
          购物袋 · {count}
        </Button>
      </div>
      <ErrorNotice error={products.error} />
      <ErrorNotice error={signalError} />
      <Modal
        className="product-modal"
        title={shown?.title}
        open={!!info}
        onCancel={() => setInfo(undefined)}
        width={720}
        footer={<Button onClick={() => setInfo(undefined)}>返回店铺</Button>}
      >
        <ErrorNotice error={details.error} />
        {details.loading && !details.data && (
          <div className="page-loading">
            <Spin description="正在加载商品详情" />
          </div>
        )}
        {shown && (
          <div className="product-detail">
            <div className="catalog-gallery product-detail-gallery">
              {(shown.images.length
                ? shown.images
                : [{ url: "", alt: shown.title }]
              ).map((image) => (
                <ProductImage
                  key={image.url || shown.skuId}
                  image={image.url ? image : undefined}
                  title={shown.title}
                />
              ))}
            </div>
            <div className="product-detail-info">
              <div className="product-detail-price">
                {money(shown.unitPrice)}
              </div>
              <p className="muted">
                优惠券与积分抵扣后，最终金额将在结算时确认。
              </p>
              <Space wrap>
                <Status value={shown.status} />
                {shown.categoryName && <Tag>{shown.categoryName}</Tag>}
                {(basket[shown.skuId] ?? 0) > 0 && (
                  <Tag>已选 {basket[shown.skuId]} 件</Tag>
                )}
              </Space>
              <Descriptions
                column={1}
                items={[
                  {
                    key: "specs",
                    label: "规格",
                    children:
                      shown.specifications
                        .map((s) => `${s.name}：${s.value}`)
                        .join(" / ") || "标准规格",
                  },
                  {
                    key: "category",
                    label: "商品类目",
                    children: shown.categoryName ?? "未分类",
                  },
                ]}
              />
              <p className="product-description">
                {shown.description || "商家尚未补充详细说明"}
              </p>
              <Space wrap className="product-detail-actions">
                <InputNumber
                  aria-label="购买数量"
                  min={1}
                  max={999}
                  precision={0}
                  value={buyQty}
                  onChange={(n) => setBuyQty(n ?? 1)}
                  disabled={shown.status !== "ACTIVE"}
                />
                <Button
                  type="primary"
                  disabled={shown.status !== "ACTIVE"}
                  onClick={() => {
                    addToBag(shown, buyQty);
                    setInfo(undefined);
                  }}
                >
                  加入购物袋
                </Button>
              </Space>
              <details className="product-meta">
                <summary>商品资料</summary>
                <p>商品标识 {shown.skuId}</p>
                <p>商品条码 {shown.barcode ?? "—"}</p>
              </details>
            </div>
          </div>
        )}
      </Modal>
      {!store ? (
        <Blank text="请先选择店铺" />
      ) : (
        <>
          <ErrorNotice error={categories.error} />
          {categories.loading && !categories.data ? (
            <Skeleton.Button
              active
              block
              style={{ height: 38, marginBottom: 18 }}
            />
          ) : browseCategories.length > 0 ? (
            <nav className="shop-channels" aria-label="商品分类">
              <Button
                className={
                  "shop-channel" + (selectedCategory ? "" : " is-active")
                }
                onClick={() => {
                  setFilters("");
                  setAfter("");
                }}
              >
                全部商品
              </Button>
              {browseCategories.map((category) => (
                <Button
                  key={category.categoryId}
                  className={
                    "shop-channel" +
                    (selectedCategory === category.categoryId
                      ? " is-active"
                      : "")
                  }
                  onClick={() => {
                    setFilters(
                      "categoryId=" + encodeURIComponent(category.categoryId),
                    );
                    setAfter("");
                  }}
                >
                  {category.name}
                </Button>
              ))}
            </nav>
          ) : null}
          <div className="shop-toolbar">
            <CatalogFilters
              shopMode
              className="shop-search"
              categories={categories.data ?? []}
              onSearch={(query) => {
                const params = new URLSearchParams(query);
                if (selectedCategory)
                  params.set("categoryId", selectedCategory);
                setFilters(params.toString());
                setAfter("");
              }}
            />
          </div>
          {products.loading && !products.data ? (
            <ProductGridSkeleton />
          ) : (
            <Row gutter={[20, 24]}>
              {products.data?.map((sku) => (
                <Col xs={12} sm={8} lg={6} key={sku.skuId}>
                  <article className="product-card">
                    <button
                      type="button"
                      className="product-media"
                      aria-label={`查看${sku.title}详情`}
                      onClick={() => openProduct(sku)}
                    >
                      <div className="product-art">
                        <ProductImage image={sku.images[0]} title={sku.title} />
                      </div>
                      {sku.status !== "ACTIVE" && (
                        <span className="product-badge">暂不可售</span>
                      )}
                    </button>
                    <div className="product-body">
                      <button
                        type="button"
                        className="product-title"
                        onClick={() => openProduct(sku)}
                      >
                        {sku.title}
                      </button>
                      <p className="product-meta-line">
                        {sku.categoryName ?? "未分类"}
                        {sku.specifications.length
                          ? ` · ${sku.specifications.map((s) => s.value).join(" / ")}`
                          : ""}
                      </p>
                      <div className="product-bottom">
                        <strong className="product-price">
                          {money(sku.unitPrice)}
                        </strong>
                        <Button
                          disabled={sku.status !== "ACTIVE"}
                          onClick={() => addToBag(sku)}
                        >
                          加入购物袋
                        </Button>
                      </div>
                      {(basket[sku.skuId] ?? 0) > 0 && (
                        <div className="bag-indicator">
                          已选 {basket[sku.skuId]} 件
                        </div>
                      )}
                    </div>
                  </article>
                </Col>
              ))}
            </Row>
          )}
          {products.data?.length === 0 && (
            <Blank
              text={filters ? "没有符合筛选条件的商品" : "这家店铺暂未上架商品"}
            />
          )}
          <Space className="shop-pager">
            <span className="list-count">
              {products.loading
                ? "正在刷新商品"
                : `本页 ${products.data?.length ?? 0} 件`}
            </span>
            <PagerActions>
              <Button disabled={!after} onClick={() => setAfter("")}>
                商品首页
              </Button>
              <Button
                disabled={products.data?.length !== 50}
                onClick={() => setAfter(products.data!.at(-1)!.skuId)}
              >
                下一页商品
              </Button>
            </PagerActions>
          </Space>
          <section className="shop-promises" aria-label="服务说明">
            <div>
              <strong>结算前确认优惠</strong>
              <p>选好商品，再查看优惠券与积分的实际抵扣。</p>
            </div>
            <div>
              <strong>订单进度随时可查</strong>
              <p>在「我的订单」中查看支付、发货与签收进度。</p>
            </div>
            <div>
              <strong>售后进度清楚可见</strong>
              <p>订单完成后可申请售后，并查看处理结果。</p>
            </div>
          </section>
        </>
      )}
      <RecordModal
        title="购物袋与结算"
        open={bag}
        onCancel={() => setBag(false)}
        width={520}
        className="checkout-modal"
      >
        <ErrorNotice error={command.error} />
        <ErrorNotice error={place.error} />
        <Steps
          responsive={false}
          titlePlacement="vertical"
          size="small"
          current={checkoutStep}
          items={[
            { title: "购物袋" },
            { title: "优惠报价" },
            { title: "确认下单" },
          ]}
          style={{ marginBottom: 20 }}
        />
        {Object.entries(basket)
          .filter(([, q]) => q > 0)
          .map(([id, n]) => (
            <div className="bag-line" key={id}>
              <span>{titles[id] ?? id}</span>
              <InputNumber
                aria-label={id + "数量"}
                min={0}
                max={999}
                precision={0}
                value={n}
                onChange={(q) => setQuantity(id, q ?? 0)}
              />
            </div>
          ))}
        {!count ? (
          <Blank text="购物袋还是空的，先挑选一件商品吧" />
        ) : (
          <>
            <Form layout="vertical">
              <Form.Item label="选择优惠券" htmlFor="checkout-coupon">
                <Select
                  id="checkout-coupon"
                  allowClear
                  placeholder="不使用优惠券"
                  value={coupon}
                  loading={coupons.loading}
                  onChange={(v) => {
                    setCoupon(v);
                    setQuote(undefined);
                  }}
                  options={coupons.data
                    ?.filter(
                      (c) => c.storeId === store && c.status === "AVAILABLE",
                    )
                    .map((c) => ({
                      value: c.couponId,
                      label: `${c.name} · 减${money(c.discountAmount)}`,
                    }))}
                />
              </Form.Item>
              <Form.Item
                label="使用积分上限"
                htmlFor="checkout-points"
                help={`可用积分 ${pointWallet.data?.available ?? "—"}，实际抵扣将在报价中确认。`}
              >
                <InputNumber
                  id="checkout-points"
                  min={0}
                  max={Math.min(1000000000, pointWallet.data?.available ?? 0)}
                  precision={0}
                  value={redeemPoints}
                  onChange={(v) => {
                    setRedeemPoints(v ?? 0);
                    setQuote(undefined);
                  }}
                  disabled={pointWallet.loading || !!pointWallet.error}
                  style={{ width: "100%" }}
                />
              </Form.Item>
              <ErrorNotice error={pointWallet.error} />
              <ErrorNotice error={coupons.error} />
            </Form>
            <Button
              type="primary"
              block
              loading={command.busy}
              onClick={createQuote}
            >
              {quote ? "重新计算优惠" : "计算优惠并结算"}
            </Button>
          </>
        )}
        {quote && (
          <div className="section checkout-quote">
            <Descriptions
              title="本次报价"
              bordered
              column={1}
              items={[
                {
                  key: "gross",
                  label: "商品金额",
                  children: money(quote.gross),
                },
                {
                  key: "discount",
                  label: "优惠合计",
                  children: money(quote.discount),
                },
                {
                  key: "points",
                  label: "积分抵扣",
                  children: quote.points
                    ? `${quote.points.points} 积分 / ${money(quote.points.discount)}`
                    : "本次未使用积分",
                },
                {
                  key: "payable",
                  label: "应付金额",
                  children: (
                    <Typography.Text strong>
                      {money(quote.payable)}
                    </Typography.Text>
                  ),
                },
              ]}
            />
            <Alert
              type="info"
              showIcon
              title="报价在5分钟内有效；提交订单时确认库存及优惠额度。"
              style={{ margin: "16px 0" }}
            />
            <Form
              form={form}
              layout="vertical"
              onFinish={async (address) => {
                const result = await place.run<Order>("/orders", {
                  quoteId: quote.quoteId,
                  address,
                });
                if (result) {
                  form.resetFields();
                  setBasket({});
                  setRedeemPoints(0);
                  pointWallet.refresh();
                  setQuote(undefined);
                  setBag(false);
                  onOrder();
                }
              }}
            >
              <Form.Item
                label="收件人"
                name="recipient"
                rules={[{ required: true, message: "请填写收件人" }]}
              >
                <Input maxLength={64} />
              </Form.Item>
              <Form.Item
                label="联系电话"
                name="phone"
                rules={[{ required: true, message: "请填写联系电话" }]}
              >
                <Input type="tel" maxLength={32} />
              </Form.Item>
              <Form.Item
                label="收货地址"
                name="detail"
                rules={[{ required: true, message: "请填写收货地址" }]}
              >
                <Input.TextArea maxLength={512} />
              </Form.Item>
              <Button
                block
                type="primary"
                htmlType="submit"
                loading={place.busy}
                disabled={place.busy}
              >
                确认下单 · {money(quote.payable)}
              </Button>
            </Form>
          </div>
        )}
      </RecordModal>
    </div>
  );
}

function ProductGridSkeleton() {
  return (
    <Row gutter={[20, 24]}>
      {Array.from({ length: 8 }, (_, index) => (
        <Col xs={12} sm={8} lg={6} key={index}>
          <div className="product-card product-skeleton">
            <Skeleton.Image active style={{ width: "100%", height: 210 }} />
            <div className="product-body">
              <Skeleton
                active
                title={{ width: "70%" }}
                paragraph={{ rows: 2 }}
              />
            </div>
          </div>
        </Col>
      ))}
    </Row>
  );
}

function ProductImage({
  image,
  title,
}: {
  image?: { url: string; alt: string };
  title: string;
}) {
  const [failed, setFailed] = useState(false);
  return image && !failed ? (
    <img
      src={image.url}
      alt={image.alt}
      referrerPolicy="no-referrer"
      loading="lazy"
      onError={() => setFailed(true)}
    />
  ) : (
    <span
      className="product-fallback"
      role="img"
      aria-label={`${title}：暂无商品图片`}
    >
      <Icon name="image" />
      图片待补充
    </span>
  );
}
