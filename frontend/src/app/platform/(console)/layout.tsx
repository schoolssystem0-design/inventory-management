import { PlatformShell } from "@/components/shell/platform-shell";

export default function PlatformLayout({ children }: LayoutProps<"/platform">) {
  return <PlatformShell>{children}</PlatformShell>;
}
