import type { Metadata } from "next";
import "./globals.css";
import Nav from "@/components/Nav";

export const metadata: Metadata = {
  title: "단타 백테스터",
  description: "규칙 기반 단타 백테스팅 시스템",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko">
      <body>
        <Nav />
        <main className="container">{children}</main>
      </body>
    </html>
  );
}
