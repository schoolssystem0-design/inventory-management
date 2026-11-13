"use client";

import { PageHeader } from "@/components/ui/layout";
import { ProductForm } from "@/features/catalog/product-form";
import { useShopHref, useShopRouter } from "@/lib/shop-context";

export default function NewProductPage() {
  const router = useShopRouter();
  const toHref = useShopHref();
  return (
    <>
      <PageHeader title="New product" back={{ href: toHref("/products"), label: "Products" }} />
      <ProductForm onSaved={(p) => router.replace(`/products/${p.id}`)} />
    </>
  );
}
