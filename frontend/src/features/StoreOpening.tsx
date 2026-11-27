import {
  Button,
  Descriptions,
  Form,
  Select,
  Space,
  Spin,
  Typography,
} from "antd";
import { useRef, useState } from "react";
import { ApiError, encode, useResource } from "../shared/api";
import { HTTP } from "../shared/http";
import { RecordModal, useRowAction } from "../shared/interactions";
import { ResourceAuditHistory } from "./OperationAudits";
import {
  CommandModal,
  ErrorNotice,
  Status,
  time,
  type Field,
} from "../shared/ui";

export const STORE_OPENINGS = "/admin/store-openings";
export const STORE_OPENING_CATEGORIES = "/admin/store-opening-categories";
export const PENDING_OPEN = "PENDING_OPEN";
export const openingSubjects = [
  { value: "PERSONAL", label: "个人" },
  { value: "INDIVIDUAL", label: "个体工商户" },
  { value: "ENTERPRISE", label: "企业" },
];
export const openingProfile: Field[] = [
  { name: "name", label: "店铺名称" },
  {
    name: "subjectType",
    label: "经营主体类型",
    type: "select",
    options: openingSubjects,
  },
];

/** 两个开店入口使用同一份平台类目，表单提交二级类目的稳定代码。 */
export function OpeningCategoryField({
  disabled,
  previousCategory,
}: {
  disabled?: boolean;
  previousCategory?: string;
}) {
  const categories = useResource<{ code: string; label: string }[]>(
    STORE_OPENING_CATEGORIES,
  );
  return (
    <>
      <ErrorNotice error={categories.error} onRetry={categories.refresh} />
      <Form.Item
        name="categoryCode"
        label="经营类目"
        extra={
          previousCategory ? `原申报类目 · ${previousCategory}` : undefined
        }
        rules={[{ required: true, message: "请选择经营类目" }]}
      >
        <Select
          placeholder="搜索大类或细类"
          showSearch={{ optionFilterProp: "label" }}
          loading={categories.loading}
          disabled={disabled || !categories.data}
          options={categories.data?.map(({ code, label }) => ({
            value: code,
            label,
          }))}
        />
      </Form.Item>
    </>
  );
}

type Opening = {
  storeId: string;
  merchantId: string;
  name: string;
  status: string;
  version: number;
  subjectType: string;
  category: string;
  categoryCode?: string;
  reviewReference?: string;
  openedBy?: string;
  openedAt?: string;
};

/** 两个管理入口复用同一份开店资料和命令，刷新后按店铺找回草稿。 */
export function StoreOpeningActions({
  storeId,
  onDone,
  onOpened,
  buttonLabel = "继续开店",
}: {
  storeId: string;
  onDone: () => void;
  onOpened?: () => void;
  buttonLabel?: string;
}) {
  const [open, setOpen] = useState(false);
  const revised = useRef(false);
  const rowAction = useRowAction();
  const path = `${STORE_OPENINGS}/${encode(storeId)}`;
  const detail = useResource<Opening>(open ? path : null);
  const opening = detail.data;
  const pending = opening?.status === PENDING_OPEN;
  function close() {
    setOpen(false);
    if (revised.current) {
      revised.current = false;
      onDone();
    }
  }
  return (
    <>
      <Button
        type={rowAction ? "link" : "default"}
        onClick={() => setOpen(true)}
      >
        {buttonLabel}
      </Button>
      <RecordModal
        title="开店资料"
        open={open}
        footer={null}
        expandable={false}
        onCancel={close}
      >
        {detail.error instanceof ApiError &&
        detail.error.status === HTTP.NOT_FOUND ? (
          <Typography.Text type="secondary">
            此店铺暂无开店资料。
          </Typography.Text>
        ) : (
          <ErrorNotice error={detail.error} onRetry={detail.refresh} />
        )}
        {detail.loading && <Spin />}
        {opening && (
          <Space orientation="vertical" size="middle">
            <Descriptions
              column={1}
              size="small"
              items={[
                { label: "店铺名称", children: opening.name },
                { label: "店铺编号", children: opening.storeId },
                { label: "所属商家", children: opening.merchantId },
                {
                  label: "经营主体",
                  children: openingSubjects.find(
                    ({ value }) => value === opening.subjectType,
                  )?.label,
                },
                { label: "经营类目", children: opening.category },
                {
                  label: "店铺状态",
                  children: <Status value={opening.status} />,
                },
                ...(opening.reviewReference
                  ? [
                      { label: "审核记录", children: opening.reviewReference },
                      { label: "开店操作人", children: opening.openedBy },
                      { label: "开店时间", children: time(opening.openedAt) },
                    ]
                  : []),
              ]}
            />
            <ResourceAuditHistory
              key={opening.version}
              path={path + "/operation-audits"}
              kind="opening"
              subjectLabels={openingSubjects}
            />
            {pending && (
              <Space wrap>
                <CommandModal
                  title="修改开店资料"
                  path={`${path}/revise`}
                  fields={openingProfile}
                  initialValues={{
                    name: opening.name,
                    subjectType: opening.subjectType,
                    categoryCode: opening.categoryCode,
                  }}
                  build={(values) => ({
                    ...values,
                    expectedVersion: opening.version,
                  })}
                  onDone={() => {
                    revised.current = true;
                    detail.refresh();
                  }}
                >
                  <OpeningCategoryField
                    previousCategory={
                      opening.categoryCode ? undefined : opening.category
                    }
                  />
                </CommandModal>
                <CommandModal
                  title="确认开店"
                  submitLabel="确认开店"
                  path={`${path}/open`}
                  fields={[
                    {
                      name: "reviewReference",
                      label: "审核记录编号",
                      help: "填写已完成的内部审核记录编号。请勿填写身份证号或上传证件。",
                    },
                  ]}
                  build={(values) => ({
                    reviewReference: String(values.reviewReference).trim(),
                    expectedVersion: opening.version,
                  })}
                  onDone={() => {
                    revised.current = false;
                    setOpen(false);
                    onDone();
                    onOpened?.();
                  }}
                >
                  <Typography.Paragraph>
                    确认后店铺在商城启用。商品与库存准备好后可开始销售。
                  </Typography.Paragraph>
                </CommandModal>
              </Space>
            )}
          </Space>
        )}
      </RecordModal>
    </>
  );
}
