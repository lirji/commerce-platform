export type IconName =
  | "overview"
  | "member"
  | "catalog"
  | "marketing"
  | "trade"
  | "platform"
  | "bag"
  | "image"
  | "arrow";

const paths: Record<IconName, string> = {
  overview: "M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z",
  member:
    "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M16 3a4 4 0 0 1 0 8M22 21v-2a4 4 0 0 0-3-3.87M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z",
  catalog: "m12 3 9 5-9 5-9-5 9-5ZM3 8v9l9 5 9-5V8M12 13v9M7.5 5.5l9 5",
  marketing: "m3 11 18-7v16L3 13v-2ZM7 14l2 7h4l-2-6",
  trade: "M6 3h12v18H6zM9 7h6M9 11h6M9 15h4",
  platform: "M4 5h16v14H4zM4 9h16M8 5v4M8 13l2 2-2 2M13 17h3",
  bag: "M5 7h14l1 14H4L5 7ZM8 7V6a4 4 0 0 1 8 0v1",
  image: "M3 3h18v18H3zM3 16l5-5 4 4 3-3 6 6M16 7h.01",
  arrow: "M5 12h14M14 7l5 5-5 5",
};

/** 使用同一组线性图标表达已有导航，避免为装饰引入第二套组件库。 */
export function Icon({
  name,
  className = "",
}: {
  name: IconName;
  className?: string;
}) {
  return (
    <svg
      className={`ui-icon ${className}`}
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d={paths[name]} />
    </svg>
  );
}
