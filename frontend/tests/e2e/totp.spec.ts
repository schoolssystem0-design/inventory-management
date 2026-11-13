import { expect, test } from "@playwright/test";
import { totp } from "./helpers";

// RFC 6238 appendix B vector (SHA-1, secret "12345678901234567890", T=59 s → 94287082 → 6 digits 287082).
test("totp helper matches the RFC vector", () => {
  expect(totp("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 59_000)).toBe("287082");
});
