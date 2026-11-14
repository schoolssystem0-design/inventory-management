import { BRAND, BRAND_ASSETS } from "@/lib/brand";
import { cn } from "cn";

/** Tirvex Tech icon or horizontal logo from brand assets. */
export function BrandMark({
  variant = "icon",
  className,
}: {
  variant?: "icon" | "logo";
  className?: string;
}) {
  const src = variant === "logo" ? BRAND_ASSETS.logo : BRAND_ASSETS.icon;
  const alt = variant === "logo" ? BRAND.company : `${BRAND.company} icon`;
  return (
    // eslint-disable-next-line @next/next/no-img-element
    <img
      src={src}
      alt={alt}
      className={cn(variant === "logo" ? "h-10 w-auto max-w-[12rem] object-contain object-left" : "size-10 rounded-xl object-cover", className)}
    />
  );
}