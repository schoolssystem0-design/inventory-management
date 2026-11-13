/** Stock adjustment reasons; `out` null = the user chooses in or out. */
export const ADJUST_REASONS = [
  { value: "DAMAGED", label: "Damaged", out: true },
  { value: "EXPIRED", label: "Expired", out: true },
  { value: "LOST", label: "Lost / stolen", out: true },
  { value: "OWN_USE", label: "Own use", out: true },
  { value: "FOUND", label: "Found", out: false },
  { value: "OTHER", label: "Other (in or out)", out: null },
] as const;
