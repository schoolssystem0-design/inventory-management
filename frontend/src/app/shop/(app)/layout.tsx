import { AppShell } from "@/components/shell/app-shell";

export default function ShopAppLayout({ children }: LayoutProps<"/shop">) {
  return <AppShell>{children}</AppShell>;
}
