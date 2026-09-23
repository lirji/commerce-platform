import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Row,
  Select,
  Space,
  Typography,
} from "antd";
import { useState } from "react";
import type { Coupon, Order, Quote, Sku } from "../shared/contracts";
import { encode, post, useCommand, useResource } from "../shared/api";
import { Blank, ErrorNotice, PageHead, money } from "../shared/ui";
export function Shop({
  store,
  onOrder,
}: {
  store: string;
  onOrder: () => void;
}) {
  const products = useResource<Sku[]>(
    store ? "/catalog?storeId=" + encode(store) : null,
  );
  const coupons = useResource<Coupon[]>("/coupons");
  const [info, setInfo] = useState<Sku>();
  const [signalError, setSignalError] = useState<Error>();
  const signal = (kind: "BROWSE" | "ADD_TO_CART", skuId: string) => {
    const eventId = crypto.randomUUID();
    // 交互记录失败不会丢掉购物袋，错误可见但不改变购物决策。
    post("/members/me/behavior/events", { eventId, kind, storeId: store, skuId }, eventId)
      .then(() => setSignalError(undefined)).catch(e => setSignalError(e instanceof Error ? e : new Error("暂时无法保存互动记录")));
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
  const setQuantity = (id: string, n: number) => {
    setBasket((b) => ({ ...b, [id]: n }));
    setQuote(undefined);
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
  return (
    <>
      <PageHead
        title="发现日常好物"
        description="挑选商品，结算时自动计算会员活动与优惠。"
        extra={
          <Button type="primary" onClick={() => setBag(true)}>
            购物袋 · {count}
          </Button>
        }
      />
      <ErrorNotice error={products.error} />
      <ErrorNotice error={signalError} />
      <Modal title={info?.title} open={!!info} onCancel={() => setInfo(undefined)} footer={<Button onClick={() => setInfo(undefined)}>返回店铺</Button>}>
        <Descriptions items={[{ key: "price", label: "当前售价", children: money(info?.unitPrice) }, { key: "sku", label: "商品标识", children: info?.skuId }]} />
      </Modal>
      {!store ? (
        <Blank text="请先选择店铺" />
      ) : (
        <>
          <div className="shop-banner">
            <div>
              <div className="eyebrow">YOUR EVERYDAY, BETTER</div>
              <h2>
                每一份心意
                <br />
                都有好物相伴。
              </h2>
              <p>会员优惠、订单和权益，在这里统一管理。</p>
            </div>
            <div className="shop-symbol" aria-hidden="true">
              商<span>品</span>
            </div>
          </div>
          <Row gutter={[20, 20]}>
            {products.data?.map((sku, index) => (
              <Col xs={24} sm={12} lg={8} key={sku.skuId}>
                <Card className="product-card" title={<span>{sku.title}</span>} extra={<Button type="link" onClick={() => { setInfo(sku); signal("BROWSE", sku.skuId); }}>查看商品</Button>}>
                  <div
                    className={"product-art art-" + (index % 3)}
                    aria-hidden="true"
                  >
                    <span>{sku.title.slice(0, 1)}</span>
                  </div>
                  <div className="product-bottom">
                    <div>
                      <strong>{money(sku.unitPrice)}</strong>
                      <div className="muted">会员活动结算时计算</div>
                    </div>
                    <Button
                      disabled={sku.status !== "ACTIVE"}
                      onClick={() => { setQuantity(sku.skuId, (basket[sku.skuId] ?? 0) + 1); signal("ADD_TO_CART", sku.skuId); }}
                    >
                      加入购物袋
                    </Button>
                  </div>
                  {(basket[sku.skuId] ?? 0) > 0 && (
                    <div className="bag-indicator">
                      已选 {basket[sku.skuId]} 件
                    </div>
                  )}
                </Card>
              </Col>
            ))}
          </Row>
          {products.data?.length === 0 && <Blank text="这家店铺暂未上架商品" />}
        </>
      )}
      <Drawer
        title="购物袋与结算"
        open={bag}
        onClose={() => setBag(false)}
        width={620}
      >
        <ErrorNotice error={command.error} />
        <ErrorNotice error={place.error} />
        {Object.entries(basket)
          .filter(([, q]) => q > 0)
          .map(([id, n]) => (
            <div className="bag-line" key={id}>
              <span>
                {products.data?.find((s) => s.skuId === id)?.title ?? id}
              </span>
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
              <Form.Item label="使用积分上限" htmlFor="checkout-points" help={`可用积分 ${pointWallet.data?.available ?? "—"}，实际抵扣以服务端报价为准。`}>
                <InputNumber id="checkout-points" min={0} max={Math.min(1000000000, pointWallet.data?.available ?? 0)} precision={0} value={redeemPoints} onChange={v => { setRedeemPoints(v ?? 0); setQuote(undefined); }} disabled={pointWallet.loading || !!pointWallet.error} style={{ width: "100%" }} />
              </Form.Item>
              <ErrorNotice error={pointWallet.error} />
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
          <div className="section">
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
                  children: quote.points ? `${quote.points.points} 积分 / ${money(quote.points.discount)}` : "本次未使用积分",
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
                rules={[{ required: true }]}
              >
                <Input maxLength={64} />
              </Form.Item>
              <Form.Item
                label="联系电话"
                name="phone"
                rules={[{ required: true }]}
              >
                <Input type="tel" maxLength={32} />
              </Form.Item>
              <Form.Item
                label="收货地址"
                name="detail"
                rules={[{ required: true }]}
              >
                <Input.TextArea maxLength={512} />
              </Form.Item>
              <Button
                block
                type="primary"
                htmlType="submit"
                loading={place.busy}
              >
                确认下单 · {money(quote.payable)}
              </Button>
            </Form>
          </div>
        )}
      </Drawer>
    </>
  );
}
