export const tradeNumberColumnWidth = 218;

export const tradeNumberFields: Record<string, Record<string, string>> = {
  "/admin/aftersales": {
    caseId: "businessNo",
    orderId: "orderBusinessNo",
    refundId: "refundBusinessNo",
  },
  "/admin/refunds": {
    refundId: "businessNo",
    caseId: "caseBusinessNo",
    orderId: "orderBusinessNo",
  },
};

export function tradeFieldValue(
  path: string,
  field: string,
  row: Record<string, unknown>,
) {
  const numberField = tradeNumberFields[path]?.[field];
  return (numberField ? row[numberField] : undefined) ?? row[field];
}
