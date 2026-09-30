import type { Metadata } from "next";

import "./globals.css";

export const metadata: Metadata = {
  title: "Multi-Currency Agent",
  description: "An agentic orchestration layer for multi-currency accounts that transforms financial intent into policy-controlled FX execution.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <head>
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="" />
        <link
          rel="stylesheet"
          href="https://fonts.googleapis.com/css2?family=Public+Sans:wght@400;500;600;700&display=swap"
        />
      </head>
      <body className="font-sans">{children}</body>
    </html>
  );
}
