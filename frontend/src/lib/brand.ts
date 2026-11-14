/** User-visible product branding (Tirvex Tech). */
export const BRAND = {
  company: "Tirvex Tech",
  product: "Tirvex Tech POS",
  platform: "Tirvex Tech Platform",
  platformLoginTitle: "Tirvex Tech platform",
  support: () => "Tirvex Tech support",
} as const;

/** Static brand marks in /public/brand (favicon uses src/app/icon.jpg). */
export const BRAND_ASSETS = {
  icon: "/brand/tirvex-icon-512.jpg",
  logo: "/brand/tirvex-logo-dark.jpg",
  logoLight: "/brand/tirvex-logo-light.jpg",
} as const;