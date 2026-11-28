import type { Store } from "../shared/contracts";
import { BusinessId, Table } from "../shared/BusinessTable";
import { useListFilters } from "../shared/listFilters";
import { CursorBack } from "../shared/pagination";
import { useRouteState, useCursorState } from "../shared/routeState";
import { RecordModal, RowActions } from "../shared/interactions";
import {
  BatchCatalogAction,
  CatalogJobs,
  ChannelPrices,
} from "./CatalogScheduling";
import {
  Alert,
  Button,
  Card,
  Dropdown,
  Grid,
  Segmented,
  Space,
  Tabs,
  Typography,
} from "antd";
import { Icon } from "../shared/Icon";
import {
  BarcodeEditor,
  CatalogFilters,
  catalogPageSize,
  CatalogStructure,
  ProductPresentation,
  VariantCreator,
  type Category,
} from "./CatalogMerchandising";
import { useState } from "react";
import { encode, useResource } from "../shared/api";
import {
  CommandModal,
  ErrorNotice,
  ListPanel,
  PageHead,
  PrimaryCell,
  Status,
  Workbench,
  money,
  time,
  type Field,
} from "../shared/ui";

type Product = {
  productId: string;
  storeId: string;
  title: string;
  category: string;
  brand: string;
  version: number;
};
type Sku = {
  images?: { url: string; alt: string }[];
  skuId: string;
  storeId: string;
  title: string;
  unitPrice: string;
  revision: number;
  status: string;
  productId?: string;
  specifications: { name: string; value: string }[];
};
const salesStatusLabels: Record<string, string> = {
  ACTIVE: "在售",
  FROZEN: "下架",
};

type Revision = {
  revision: number;
  title: string;
  unitPrice: string;
  status: string;
  reason: string;
  actorId: string;
  createdAt: string;
};
const metadata: Field[] = [
  { name: "title", label: "商品名称" },
  { name: "category", label: "分类" },
  { name: "brand", label: "品牌" },
];
const skuFields: Field[] = [
  { name: "title", label: "销售名称" },
  { name: "unitPrice", label: "售价（元）", type: "money" },
];

/** 操作入口相同，但每次请求由服务端重新判断实际门店授权。 */
export function ProductOperations({
  store,
  embedded = false,
  storeContext,
  merchant = false,
}: {
  store: string;
  embedded?: boolean;
  storeContext?: Store;
  merchant?: boolean;
}) {
  const compact = Grid.useBreakpoint().md === false;
  const [tab, setTab] = useRouteState("tab", "sku");
  const [batch, setBatch] = useState<Sku[]>([]);
  const [channelSku, setChannelSku] = useState<Sku>();
  const [productAfter, setProductAfter] = useCursorState("productAfter", "");
  const [skuAfter, setSkuAfter] = useCursorState("skuAfter", "");
  const [filters, setFilters] = useRouteState("filters", "");
  const [presentation, setPresentation] = useState<Product>();
  const [barcode, setBarcode] = useState<Sku>();
  const categories = useResource<Category[]>(
    store ? `/operations/catalog-categories?storeId=${encode(store)}` : null,
  );
  const [selected, setSelected] = useState<Sku>();
  const [historyAfter, setHistoryAfter] = useCursorState(
    "ProductOperations.historyAfter",
    0,
  );
  const productFilters = useListFilters(
    "ProductOperations.products",
    "/operations/products",
    () => setProductAfter(""),
  );
  const products = useResource<Product[]>(
    store
      ? `/operations/products?storeId=${encode(store)}&after=${encode(productAfter)}&${productFilters.query}`
      : null,
  );
  const skus = useResource<Sku[]>(
    store
      ? `/operations/catalog-search?storeId=${encode(store)}&after=${encode(skuAfter)}&${filters}`
      : null,
  );
  const history = useResource<Revision[]>(
    selected
      ? `/operations/skus/${encode(selected.skuId)}/history?storeId=${encode(store)}&after=${historyAfter}`
      : null,
  );
  // 列表重新读取后，旧行的版本和当前筛选范围都可能失效，批量计划必须重新勾选。
  const refresh = () => {
    setBatch([]);
    products.refresh();
    skus.refresh();
    history.refresh();
    categories.refresh();
  };
  const searchSku = (query: string) => {
    setBatch([]);
    setFilters(query);
    setSkuAfter("");
  };
  const skuStatus = new URLSearchParams(filters).get("status") ?? "";
  const changeSkuPage = (cursor: string) => {
    setBatch([]);
    setSkuAfter(cursor);
  };
  if (!store) return <Alert type="info" title="请先选择有经营权限的门店" />;
  return (
    <Workbench>
      {!embedded && (
        <PageHead
          eyebrow="商品与门店"
          title="商品经营"
          description={
            merchant
              ? "管理授权门店的销售规格、价格和上下架计划。"
              : "维护门店商品资料、销售规格与上下架计划。"
          }
        />
      )}
      {!embedded && (
        <section className="design-store-scope" aria-label="商品经营范围">
          <div className="design-store-symbol">
            <Icon name="catalog" />
          </div>
          <div className="design-store-name">
            <span>{merchant ? "当前授权门店" : "当前经营门店"}</span>
            <strong>{storeContext?.name ?? store}</strong>
          </div>
          {storeContext && (
            <>
              <Status value={storeContext.status} />
              <dl>
                <div>
                  <dt>门店编号</dt>
                  <dd>
                    <BusinessId value={store} />
                  </dd>
                </div>
                <div>
                  <dt>商家编号</dt>
                  <dd>
                    <BusinessId value={storeContext.merchantId} />
                  </dd>
                </div>
              </dl>
            </>
          )}
        </section>
      )}
      <Tabs
        activeKey={tab}
        onChange={setTab}
        items={[
          {
            key: "sku",
            label: "销售规格与上下架",
            children: (
              <ListPanel
                toolbar={
                  <>
                    <ErrorNotice error={skus.error} onRetry={skus.refresh} />
                    <div
                      className={
                        !embedded ? "design-catalog-toolbar" : undefined
                      }
                    >
                      {!embedded && (
                        <Segmented
                          aria-label="销售规格视图"
                          options={[
                            { label: "全部规格", value: "" },
                            { label: "在售", value: "ACTIVE" },
                            { label: "下架", value: "FROZEN" },
                          ]}
                          value={skuStatus}
                          onChange={(status) => {
                            const query = new URLSearchParams(filters);
                            if (status) query.set("status", status);
                            else query.delete("status");
                            searchSku(query.toString());
                          }}
                        />
                      )}
                      <Space wrap>
                        <VariantCreator store={store} onDone={refresh} />
                        <BatchCatalogAction
                          store={store}
                          rows={batch}
                          onDone={refresh}
                        />
                        <Button loading={skus.loading} onClick={refresh}>
                          刷新
                        </Button>
                      </Space>
                    </div>
                    <CatalogFilters
                      query={filters}
                      categories={categories.data ?? []}
                      operations
                      showStatusSelect={embedded}
                      onSearch={searchSku}
                    />
                  </>
                }
                pageSize={catalogPageSize(filters)}
                count={skus.data?.length ?? 0}
                loading={skus.loading}
                error={skus.error}
                after={skuAfter}
                cursorName={"skuAfter"}
                onBack={() => setBatch([])}
                onHome={() => changeSkuPage("")}
                onNext={() => changeSkuPage(skus.data!.at(-1)!.skuId)}
                homeLabel="回到首页"
              >
                <Table<Sku>
                  tableId="sku"
                  selectedUnit="个规格"
                  rowSelection={{
                    selectedRowKeys: batch.map((r) => r.skuId),
                    onChange: (_, rows) => setBatch(rows),
                  }}
                  rowKey="skuId"
                  dataSource={skus.data}
                  loading={skus.loading}
                  pagination={false}
                  scroll={{ x: embedded ? 1150 : 1110 }}
                  columns={[
                    {
                      title: embedded ? "商品" : "规格名称",
                      dataIndex: "title",
                      width: embedded ? 250 : 194,
                      render: (_, r) => (
                        <div className="catalog-identity">
                          <span className="catalog-thumbnail">
                            <Icon name="image" />
                            {r.images?.[0] && (
                              <img
                                src={r.images[0].url}
                                alt={r.images[0].alt || r.title}
                                loading="lazy"
                                onError={(event) => {
                                  event.currentTarget.hidden = true;
                                }}
                              />
                            )}
                          </span>
                          {embedded ? (
                            <PrimaryCell
                              title={r.title}
                              subtitle={<BusinessId value={r.skuId} />}
                            />
                          ) : (
                            <strong className="design-catalog-title">
                              {r.title}
                            </strong>
                          )}
                        </div>
                      ),
                    },
                    ...(!embedded
                      ? [
                          {
                            title: "规格编号",
                            dataIndex: "skuId",
                            width: 130,
                          },
                        ]
                      : []),
                    {
                      title: "规格",
                      width: embedded ? 220 : 150,
                      render: (_, r) =>
                        r.specifications
                          .map((s) => `${s.name}：${s.value}`)
                          .join(" / ") || "—",
                    },
                    {
                      title: "售价",
                      align: "right",
                      dataIndex: "unitPrice",
                      render: money,
                      width: 100,
                    },
                    {
                      title: "状态",
                      width: 90,
                      dataIndex: "status",
                      render: (v: string) => (
                        <Status
                          value={v}
                          label={!embedded ? salesStatusLabels[v] : undefined}
                        />
                      ),
                    },
                    ...(!merchant
                      ? [
                          {
                            title: "门店编号",
                            dataIndex: "storeId",
                            width: 110,
                          },
                        ]
                      : []),
                    {
                      title: "SPU",
                      width: embedded ? 130 : 110,
                      render: (_, r) => r.productId ?? "历史商品",
                    },
                    { title: "版本", dataIndex: "revision", width: 64 },
                    {
                      title: "操作",
                      width: 172,
                      fixed: compact ? undefined : "right",
                      className: "row-actions-cell",
                      render: (_, r) => (
                        <RowActions>
                          <CommandModal
                            key={`${r.skuId}:${r.revision}`}
                            title="调价 / 上下架"
                            path={"/operations/skus/" + encode(r.skuId)}
                            buttonType="link"
                            fields={[
                              ...skuFields,
                              {
                                name: "status",
                                label: "销售状态",
                                type: "select",
                                options: [
                                  { label: "上架", value: "ACTIVE" },
                                  { label: "下架", value: "FROZEN" },
                                ],
                              },
                              { name: "reason", label: "修订原因" },
                            ]}
                            initialValues={{
                              title: r.title,
                              unitPrice: r.unitPrice,
                              status: r.status,
                            }}
                            build={(v) => ({
                              ...v,
                              storeId: store,
                              expectedVersion: r.revision,
                            })}
                            onDone={refresh}
                          />
                          <Dropdown
                            trigger={["click"]}
                            autoFocus
                            menu={{
                              items: [
                                {
                                  key: "channel-price",
                                  label: "渠道价",
                                  onClick: () => setChannelSku(r),
                                },
                                {
                                  key: "barcode",
                                  label: "条码资料",
                                  onClick: () => setBarcode(r),
                                },
                                {
                                  key: "revision-history",
                                  label: "修订记录",
                                  onClick: () => {
                                    setHistoryAfter(0);
                                    setSelected(r);
                                  },
                                },
                              ],
                            }}
                          >
                            <Button
                              type="link"
                              aria-label="更多规格操作"
                              onClick={(event) =>
                                event.currentTarget.focus({
                                  preventScroll: true,
                                })
                              }
                            >
                              更多
                            </Button>
                          </Dropdown>
                        </RowActions>
                      ),
                    },
                  ]}
                />
              </ListPanel>
            ),
          },
          {
            key: "product",
            label: "商品资料（SPU）",
            children: (
              <ListPanel
                toolbar={
                  <>
                    {productFilters.toolbar}
                    <ErrorNotice
                      error={products.error}
                      onRetry={products.refresh}
                    />
                    <CommandModal
                      title="创建商品"
                      path="/operations/products"
                      fields={[
                        { name: "productId", label: "商品标识" },
                        ...metadata,
                      ]}
                      build={(v) => ({ ...v, storeId: store })}
                      onDone={refresh}
                    />
                  </>
                }
                pageSize={productFilters.limit}
                count={products.data?.length ?? 0}
                loading={products.loading}
                error={products.error}
                after={productAfter}
                cursorName={"productAfter"}
                onHome={() => setProductAfter("")}
                onNext={() => setProductAfter(products.data!.at(-1)!.productId)}
                homeLabel="回到首页"
              >
                <Table<Product>
                  rowKey="productId"
                  scroll={!embedded ? { x: 690 } : undefined}
                  dataSource={products.data}
                  loading={products.loading}
                  pagination={false}
                  columns={[
                    {
                      title: embedded ? "商品" : "商品名称",
                      dataIndex: "title",
                      render: (_, r) =>
                        embedded ? (
                          <PrimaryCell
                            title={r.title}
                            subtitle={<BusinessId value={r.productId} />}
                          />
                        ) : (
                          <strong>{r.title}</strong>
                        ),
                    },
                    ...(!embedded
                      ? [
                          {
                            title: "商品编号",
                            dataIndex: "productId",
                            width: 150,
                          },
                        ]
                      : []),
                    { title: "分类", dataIndex: "category" },
                    { title: "品牌", dataIndex: "brand" },
                    {
                      title: "操作",
                      className: "row-actions-cell",
                      render: (_, r) => (
                        <RowActions>
                          <Button
                            type="link"
                            onClick={() => setPresentation(r)}
                          >
                            展示资料
                          </Button>
                          <CommandModal
                            key={`${r.productId}:${r.version}`}
                            title="编辑资料"
                            path={"/operations/products/" + encode(r.productId)}
                            buttonType="link"
                            fields={metadata}
                            initialValues={{
                              title: r.title,
                              category: r.category,
                              brand: r.brand,
                            }}
                            build={(v) => ({
                              ...v,
                              storeId: store,
                              expectedVersion: r.version,
                            })}
                            onDone={refresh}
                          />
                        </RowActions>
                      ),
                    },
                  ]}
                />
              </ListPanel>
            ),
          },
          {
            key: "jobs",
            label: "批量定时计划",
            children: <CatalogJobs store={store} />,
          },
          {
            key: "structure",
            label: "类目与模板",
            children: <CatalogStructure store={store} />,
          },
        ]}
      />
      <ProductPresentation
        store={store}
        product={presentation}
        onClose={() => setPresentation(undefined)}
      />
      <ChannelPrices
        store={store}
        sku={channelSku}
        onClose={() => setChannelSku(undefined)}
      />
      <BarcodeEditor
        store={store}
        sku={barcode}
        onClose={() => setBarcode(undefined)}
        onDone={refresh}
      />
      <RecordModal
        className="record-modal"
        title={`${selected?.title ?? "商品"} · 修订记录`}
        open={!!selected}
        onCancel={() => setSelected(undefined)}
        width={760}
      >
        {selected && (
          <Card className="record-identity" variant="borderless">
            <div className="record-identity-heading">
              <div>
                <span className="page-eyebrow">销售规格 / 修订审计</span>
                <Typography.Title level={3}>{selected.title}</Typography.Title>
                <Typography.Text type="secondary" copyable>
                  {selected.skuId}
                </Typography.Text>
              </div>
              <Status value={selected.status} />
            </div>
            <div className="record-facts">
              <div>
                <span>列表售价</span>
                <strong>{money(selected.unitPrice)}</strong>
              </div>
              <div>
                <span>列表版本</span>
                <strong>v{selected.revision}</strong>
              </div>
              <div>
                <span>销售规格</span>
                <strong>
                  {selected.specifications
                    .map((spec) => `${spec.name}：${spec.value}`)
                    .join(" / ") || "—"}
                </strong>
              </div>
            </div>
          </Card>
        )}
        <ErrorNotice error={history.error} onRetry={history.refresh} />
        <Table<Revision>
          rowKey="revision"
          dataSource={history.data}
          scroll={{ x: 780 }}
          loading={history.loading}
          pagination={false}
          columns={[
            { title: "版本", dataIndex: "revision" },
            {
              title: "售价",
              dataIndex: "unitPrice",
              align: "right",
              render: money,
            },
            {
              title: "状态",
              dataIndex: "status",
              render: (v) => <Status value={v} />,
            },
            { title: "原因", dataIndex: "reason" },
            { title: "操作人", dataIndex: "actorId" },
            { title: "时间", dataIndex: "createdAt", render: time },
          ]}
        />
        <div className="pager-navigation">
          <CursorBack
            name={"ProductOperations.historyAfter"}
            after={historyAfter}
            onPrevious={setHistoryAfter}
            initial={0}
            disabled={history.loading || !!history.error}
            count={history.data?.length}
            pageSize={50}
          />
          <Button onClick={() => setHistoryAfter(0)}>最早记录</Button>
          <Button
            disabled={
              history.loading || !!history.error || history.data?.length !== 50
            }
            onClick={() => setHistoryAfter(history.data!.at(-1)!.revision)}
          >
            下一页
          </Button>
        </div>
      </RecordModal>
    </Workbench>
  );
}
