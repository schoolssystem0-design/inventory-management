import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { visibleNav } from "@/components/shell/nav";
import { Field, Input } from "@/components/ui/fields";
import { ImportResultView } from "@/features/catalog/import-result";
import { toApiError } from "@/lib/api/client";

describe("navigation", () => {
  it("shows only what the user may open", () => {
    const cashier = new Set(["catalog.view", "pos.sell"]);
    const labels = visibleNav((p) => cashier.has(p)).flatMap((g) => g.items.map((i) => i.label));
    expect(labels).toContain("Products");
    expect(labels).toContain("My account");
    expect(labels).not.toContain("Users");
    expect(labels).not.toContain("Settings");
    expect(labels).not.toContain("Import from Excel");

    const owner = visibleNav(() => true).flatMap((g) => g.items.map((i) => i.label));
    expect(owner).toEqual(expect.arrayContaining(["Users", "Settings", "Change prices", "Barcode labels"]));
  });
});

describe("api errors", () => {
  it("reads the problem response with field errors and extra properties", async () => {
    const response = new Response(
      JSON.stringify({ code: "plan_limit", detail: "Your plan allows 2 users.", limit: "users", errors: [{ field: "email", code: "invalid", message: "Bad email" }] }),
      { status: 402 },
    );
    const error = await toApiError(response);
    expect(error.status).toBe(402);
    expect(error.code).toBe("plan_limit");
    expect(error.message).toBe("Your plan allows 2 users.");
    expect(error.field("email")).toBe("Bad email");
    expect(error.extra.limit).toBe("users");
  });

  it("copes with a response that is not JSON", async () => {
    const error = await toApiError(new Response("oops", { status: 502, statusText: "Bad Gateway" }));
    expect(error.code).toBe("http_502");
  });
});

describe("Field", () => {
  it("links the label, the input and the error for screen readers", () => {
    render(
      <Field label="Price" error="Enter an amount">
        <Input />
      </Field>,
    );
    const input = screen.getByLabelText("Price");
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(input).toHaveAccessibleDescription("Enter an amount");
  });
});

describe("ImportResultView", () => {
  it("lists row problems", () => {
    vi.stubGlobal("ResizeObserver", class { observe() {} unobserve() {} disconnect() {} });
    render(
      <ImportResultView
        result={{
          committed: false,
          rows: 3,
          toCreate: 2,
          toUpdate: 0,
          errors: [{ row: 4, column: "Price", message: "Enter an amount like 3500 or 3500.50" }],
          newCategories: ["Pain relief"],
          newBrands: [],
          newUnits: [],
          ignoredColumns: ["Notes"],
        }}
      />,
    );
    expect(screen.getByText("Enter an amount like 3500 or 3500.50")).toBeInTheDocument();
    expect(screen.getByText(/1 problem to fix/)).toBeInTheDocument();
    expect(screen.getByText("New categories: Pain relief")).toBeInTheDocument();
  });
});
