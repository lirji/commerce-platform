/** 与S4–S10公开DTO对齐；金额由服务端精确计算并以字符串传输。 */
export type Actor = {
  tenantId: string;
  actorId: string;
  role: "ADMIN" | "MEMBER" | "OPERATOR";
};
export type Capabilities = { sandboxEnabled: boolean; workersEnabled: boolean };
export type Store = {
  storeId: string;
  merchantId: string;
  name: string;
  status: string;
  version: number;
};
export type Sku = {
  skuId: string;
  storeId: string;
  title: string;
  unitPrice: string;
  status: string;
  version: number;
};
export type Rule = {
  kind: "COMPARE" | "ALL" | "ANY" | "NOT";
  field?: string;
  operator?: string;
  valueType?: string;
  value?: string;
  children?: Rule[];
};
export type AssetRef = { id: string; version: number };
export type BenefitRef = { benefitId: string; version: number };
export type Campaign = {
  campaignId: string;
  version: number;
  storeId: string;
  name: string;
  validFrom: string;
  validTo: string;
  minimumSpend: string;
  discountAmount: string;
  rule: Rule | null;
  policy?: {
    audience?: AssetRef;
    rule?: AssetRef;
    terms?: {
      percentageBps: number;
      platformFundingBps: number;
      budget: string | null;
      grant?: BenefitRef;
      pricing?: {includedSkuIds:string[];excludedSkuIds:string[];tiers:{minimumSpend:string;discountAmount:string;percentageBps:number}[]};
    };
  };
};
export type Governed<T> = { content: T; status: string; lockVersion: number };
export type CouponDefinition = {
  issuanceMode?: "PUBLIC" | "SOURCE_ONLY";
  validityDays?: number | null;
  definitionId: string;
  version: number;
  storeId: string;
  name: string;
  minimumSpend: string;
  discountAmount: string;
  validFrom: string;
  validTo: string;
  quota: number;
  stackable: boolean;
  platformFundingBps?: number;
};
export type Coupon = {
  couponId: string;
  definitionId: string;
  version: number;
  memberId: string;
  storeId: string;
  name: string;
  status: string;
  discountAmount: string;
  minimumSpend: string;
  validFrom: string;
  validTo: string;
  stackable: boolean;
  platformFundingBps: number;
};
export type Entitlement = {
  grantId: string;
  orderId: string | null;
  memberId: string;
  name: string;
  status: string;
  units: number;
  remainingUnits: number;
  debtUnits: number;
  expiresAt: string | null;
  version: number;
};
export type QuoteLine = {
  skuId: string;
  title: string;
  quantity: number;
  unitPrice: string;
  gross: string;
  discount: string;
  payable: string;
  points?: number;
  pointDiscount?: string;
};
export type Quote = {
  quoteId: string;
  storeId: string;
  gross: string;
  discount: string;
  payable: string;
  expiresAt: string;
  items: QuoteLine[];
  campaign?: { campaignId: string };
  couponStatus?: string;
  points?: { policyVersion: number; points: number; discount: string } | null;
  trace: unknown;
};
export type Order = {
  orderId: string;
  memberId: string;
  storeId: string;
  payable: string;
  status: string;
  paymentKind: string;
  version: number;
  createdAt: string;
  expiresAt: string;
  items: QuoteLine[];
};
export type Payment = {
  paymentId: string;
  orderId: string;
  amount: string;
  status: string;
  provider: string;
  currency: string;
};
export type Aftersale = {
  caseId: string;
  orderId: string;
  memberId: string;
  status: string;
  refundAmount: string;
  returnRequired: boolean;
  refundId: string | null;
  items: { skuId: string; quantity: number; refundAmount: string; points?: number }[];
};
export type Fulfillment = {
  orderId: string;
  status: string;
  trackingNo: string | null;
  provider: string;
  blocked: boolean;
  version: number;
};
export type Refund = {
  refundId: string;
  orderId: string;
  caseId: string;
  amount: string;
  status: string;
  provider: string;
};
export type JourneyNode = {
  id: string;
  kind: "WAIT" | "DECIDE" | "GRANT" | "NOTIFY" | "END";
  seconds?: number;
  next?: string;
  rule?: Rule;
  yesNext?: string;
  noNext?: string;
  benefit?: BenefitRef;
  title?: string;
  body?: string;
};
export type Journey = {
  journeyId: string;
  version: number;
  storeId: string;
  name: string;
  trigger: "MANUAL" | "ORDER_PAID" | "MEMBER_REGISTERED" | "LEVEL_CHANGED" | "SEGMENT_ENTERED";
  validFrom: string;
  validTo: string;
  maxDurationSeconds: number;
  entry: string;
  nodes: JourneyNode[];
  controls?: {segmentId?:string;entryRule?:Rule;maxEntries:number;entryWindowSeconds:number;notificationLimit:number;notificationWindowSeconds:number};
};
export type JourneyInstance = {
  instanceId: string;
  journeyId: string;
  journeyVersion: number;
  memberId: string;
  orderId: string | null;
  currentNode: string;
  status: string;
  dueAt: string;
  deadline: string;
  steps: number;
  attempts: number;
  result: string | null;
};
export type Notification = {
  notificationId: string;
  title: string;
  body: string;
  createdAt: string;
};
export type PageSource =
  "CAMPAIGNS" | "JOURNEYS" | "BUDGETS" | "COUPONS" | "ENTITLEMENTS";
export type PageActionKind =
  "CREATE_CAMPAIGN" | "CREATE_COUPON" | "ENROLL_JOURNEY";
export type PageDefinition = {
  pageId: string;
  version: number;
  title: string;
  storeId: string;
  sections: { id: string; title: string; source: PageSource }[];
  actions: { id: string; label: string; kind: PageActionKind }[];
};
export type PageRender = {
  page: Governed<PageDefinition>;
  data: { id: string; rows: unknown[] }[];
  preview: boolean;
  bounded: boolean;
};
export type Budget = {
  budgetId: string;
  campaignId: string;
  version: number;
  cap: string | null;
  held: string;
  spent: string;
};
export type Event = {
  eventId: string;
  eventType: string;
  aggregateId: string;
  status: string;
  attempts: number;
  availableAt: string;
};
