import type { MetadataRoute } from "next";
import { BRAND } from "@/lib/brand";

/** Installable app (HW-07): opens full screen from the desktop or home screen. */
export default function manifest(): MetadataRoute.Manifest {
  return {
    name: BRAND.product,
    short_name: "Tirvex POS",
    description: "Inventory and point of sale",
    start_url: "/",
    display: "standalone",
    background_color: "#141415",
    theme_color: "#141415",
    icons: [
      { src: "/brand/tirvex-icon-512.jpg", sizes: "512x512", type: "image/jpeg", purpose: "any" },
      { src: "/brand/tirvex-icon-512.jpg", sizes: "512x512", type: "image/jpeg", purpose: "maskable" },
    ],
  };
}
