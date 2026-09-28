import {
  Button,
  Drawer as AntDrawer,
  Modal,
  Space,
  type DrawerProps,
  type FormInstance,
} from "antd";
import {
  Children,
  isValidElement,
  createContext,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";

const RowActionContext = createContext(false);
export const useRowAction = () => useContext(RowActionContext);

/** 行操作只影响入口按钮，弹层内的表单仍使用正常尺寸。 */
export function RowActions({ children }: { children: ReactNode }) {
  return (
    <RowActionContext.Provider value>
      <div className="row-actions" role="group" aria-label="记录操作">
        {children}
      </div>
    </RowActionContext.Provider>
  );
}

export function FormActions({
  onCancel,
  busy,
  children,
}: {
  onCancel: () => void;
  busy?: boolean;
  children: ReactNode;
}) {
  return (
    <div className="form-actions">
      <Button disabled={busy} onClick={onCancel}>
        取消
      </Button>
      {children}
    </div>
  );
}

/** 关闭保护只针对用户改动；接口请求进行中不能销毁输入上下文。 */
export function useDirtyClose(
  form: FormInstance,
  busy: boolean,
  onClose: () => void,
) {
  const [modal, contextHolder] = Modal.useModal();
  const requestClose = () => {
    if (busy) return;
    if (!form.isFieldsTouched()) return onClose();
    modal.confirm({
      title: "放弃本次修改？",
      content: "尚未保存的内容将被清除。你也可以继续编辑后再保存。",
      okText: "放弃修改",
      cancelText: "继续编辑",
      onOk: onClose,
    });
  };
  return { requestClose, contextHolder };
}

/** 轻量详情保留列表上下文，长内容可临时展开，窄屏在弹层内部滚动。 */
export function RecordDrawer({
  children,
  className,
  size,
  width,
  extra,
  footer,
  onClose,
  open,
  ...props
}: DrawerProps) {
  const [expanded, setExpanded] = useState(false);
  useEffect(() => {
    if (!open) setExpanded(false);
  }, [open]);
  return (
    <AntDrawer
      {...props}
      open={open}
      onClose={onClose}
      className={["record-drawer", className].filter(Boolean).join(" ")}
      size={expanded ? 1120 : (size ?? width ?? 720)}
      extra={
        <div className="drawer-header-actions">
          {extra}
          <Button
            type="text"
            size="small"
            onClick={() => setExpanded((v) => !v)}
          >
            {expanded ? "收起视图" : "展开视图"}
          </Button>
        </div>
      }
      footer={
        footer === undefined ? (
          <div className="drawer-read-actions">
            <Button onClick={onClose}>返回列表</Button>
          </div>
        ) : (
          footer
        )
      }
    >
      {children}
    </AntDrawer>
  );
}

/** 子列表仍沿用各自游标契约；没有可导航方向时收起按钮组。 */
export function PagerActions({ children }: { children: ReactNode }) {
  const buttons = Children.toArray(children);
  const unavailable =
    buttons.length > 0 &&
    buttons.every(
      (child) =>
        isValidElement<{ disabled?: boolean }>(child) &&
        child.type === Button &&
        child.props.disabled,
    );
  return unavailable ? null : (
    <Space className="pager-navigation">{children}</Space>
  );
}
