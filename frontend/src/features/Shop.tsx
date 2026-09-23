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
  Row,
  Select,
  Space,
  Typography,
} from "antd";
import { useState } from "react";
import type { Coupon, Order, Quote, Sku } from "../shared/contracts";
import { encode, useCommand, useResource } from "../shared/api";
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
  const [basket, setBasket] = useState<Record<string, number>>({});
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
                <Card className="product-card" title={<span>{sku.title}</span>}>
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
                      onClick={() =>
                        setQuantity(sku.skuId, (basket[sku.skuId] ?? 0) + 1)
                      }
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
