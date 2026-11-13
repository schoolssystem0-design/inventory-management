import type { MetadataRoute } from "next";

/** Installable app (HW-07): opens full screen from the desktop or home screen. */
export default function manifest(): MetadataRoute.Manifest {
  return {
    name: "SelectCode POS",
    short_name: "POS",
    description: "Inventory and point of sale",
    start_url: "/",
    display: "standalone",
    background_color: "#141415",
    theme_color: "#141415",
    icons: [
      { src: "/icons/icon.svg", sizes: "any", type: "image/svg+xml", purpose: "any" },
      { src: "/icons/icon-maskable.svg", sizes: "any", type: "image/svg+xml", purpose: "maskable" },
    ],
  };
}
