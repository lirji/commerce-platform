import { Button, Modal, type ModalProps, type FormInstance } from "antd";
import {
  Children,
  isValidElement,
  createContext,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";

const RowActionContext = createContext(false);
// 下拉菜单通过Portal挂载且关闭后卸载，不能把已消失的菜单项当作详情返回位置。
let lastRowTrigger: WeakRef<HTMLElement> | undefined;
export const useRowAction = () => useContext(RowActionContext);

/** 行操作仅区分入口的视觉语义；入口和弹层内按钮共用全站尺寸。 */
export function RowActions({ children }: { children: ReactNode }) {
  return (
    <RowActionContext.Provider value>
      <div
        className="row-actions"
        role="group"
        aria-label="记录操作"
        onFocusCapture={(event) => {
          if (
            event.target instanceof HTMLElement &&
            event.currentTarget.contains(event.target)
          )
            lastRowTrigger = new WeakRef(event.target);
        }}
      >
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
      centered: true,
      title: "放弃本次修改？",
      content: "尚未保存的内容将被清除。你也可以继续编辑后再保存。",
      okText: "放弃修改",
      cancelText: "继续编辑",
      onOk: onClose,
    });
  };
  return { requestClose, contextHolder };
}

/** 居中弹层保留列表上下文；长内容内部滚动，表单操作始终可见。 */
export function RecordModal({
  children,
  className,
  width = 560,
  extra,
  footer,
  onCancel,
  open,
  title,
  initialExpanded = false,
  expandable = true,
  ...props
}: Omit<ModalProps, "onCancel"> & {
  onCancel?: () => void;
  extra?: ReactNode;
  initialExpanded?: boolean;
  expandable?: boolean;
}) {
  const [expanded, setExpanded] = useState(initialExpanded);
  const returnFocus = useRef<HTMLElement | null>(null);
  useEffect(() => {
    if (!open) return;
    const active = document.activeElement;
    returnFocus.current =
      active instanceof HTMLElement &&
      active !== document.body &&
      !active.closest(".ant-dropdown, .ant-modal")
        ? active
        : (lastRowTrigger?.deref() ?? null);
    lastRowTrigger = undefined;
  }, [open]);
  useEffect(() => {
    if (!open) setExpanded(initialExpanded);
  }, [open, initialExpanded]);
  return (
    <Modal
      {...props}
      centered
      open={open}
      onCancel={onCancel}
      afterClose={() => {
        // 对象/路由已改变时不聚焦旧节点；嵌套确认仍打开时不把焦点移出对话框。
        const anotherDialog = Array.from(
          document.querySelectorAll('[role="dialog"]'),
        ).some((dialog) => dialog.getClientRects().length > 0);
        if (!anotherDialog && returnFocus.current?.isConnected)
          returnFocus.current.focus({ preventScroll: true });
        props.afterClose?.();
      }}
      className={[
        "record-modal",
        expanded && "record-modal-expanded",
        className,
      ]
        .filter(Boolean)
        .join(" ")}
      width={expanded ? 960 : width}
      title={
        <div className="record-modal-heading">
          <span>{title}</span>
          <div className="modal-header-actions">
            {extra}
            {expandable && (
              <Button
                type="text"
                className="modal-expand-control"
                onClick={() => setExpanded((value) => !value)}
              >
                {expanded ? "收起视图" : "展开视图"}
              </Button>
            )}
          </div>
        </div>
      }
      footer={
        footer === undefined ? (
          <div className="modal-read-actions">
            <Button onClick={onCancel}>返回列表</Button>
          </div>
        ) : (
          footer
        )
      }
    >
      {children}
    </Modal>
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
  // CursorBack 返回多个节点，使用原生 flex 让摘要、页码和按钮都参与间距布局。
  return unavailable ? null : (
    <div className="pager-navigation">{children}</div>
  );
}
