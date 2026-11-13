import type { Metadata, Viewport } from "next";
import { JetBrains_Mono, Manrope, Noto_Nastaliq_Urdu } from "next/font/google";
import { Providers } from "./providers";
import "./globals.css";

const body = Manrope({ subsets: ["latin"], variable: "--font-body" });
const code = JetBrains_Mono({ subsets: ["latin"], variable: "--font-code" });
const urdu = Noto_Nastaliq_Urdu({ subsets: ["arabic"], variable: "--font-nastaliq", weight: ["400", "600"] });

export const metadata: Metadata = {
  title: { default: "SelectCode POS", template: "%s · SelectCode POS" },
  description: "Inventory and point of sale for every kind of shop",
  applicationName: "SelectCode POS",
  appleWebApp: { capable: true, title: "SelectCode POS", statusBarStyle: "black-translucent" },
};

export const viewport: Viewport = {
  themeColor: "#141415",
  width: "device-width",
  initialScale: 1,
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    // suppressHydrationWarning: browser extensions add attributes to <html>/<body> before React hydrates.
    <html lang="en" className={`${body.variable} ${code.variable} ${urdu.variable}`} suppressHydrationWarning>
      <body className="min-h-dvh font-sans antialiased" suppressHydrationWarning>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
