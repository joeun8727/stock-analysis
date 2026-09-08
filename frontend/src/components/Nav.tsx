"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const LINKS = [
  { href: "/strategies", label: "로직 관리" },
  { href: "/datasets", label: "데이터 업로드" },
  { href: "/etf-groups", label: "ETF 그룹" },
  { href: "/backtest", label: "백테스트" },
  { href: "/live", label: "실투자" },
  { href: "/fees", label: "수수료" },
  { href: "/guide", label: "매도 조건 가이드" },
];

export default function Nav() {
  const path = usePathname();
  return (
    <nav className="nav">
      <span className="brand">📈 단타 백테스터</span>
      {LINKS.map((l) => (
        <Link key={l.href} href={l.href} className={path?.startsWith(l.href) ? "active" : ""}>
          {l.label}
        </Link>
      ))}
    </nav>
  );
}
