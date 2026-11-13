/**
 * Weighing-scale labels (SUP-03): EAN-13 starting 20–29, the 5-digit item code (the product's scale code), a
 * 5-digit value and a check digit. The value is the weight in grams or the price in whole rupees (shop setting).
 * Same rules as the server's ScaleBarcode (docs/test-vectors/promotions.json).
 */
export type ScaleReading = { itemCode: string; value: string };

function checkDigit(twelve: string): number {
  let sum = 0;
  for (let i = 0; i < 12; i++) {
    const d = twelve.charCodeAt(i) - 48;
    sum += i % 2 === 0 ? d : d * 3;
  }
  return (10 - (sum % 10)) % 10;
}

/** null when the code is not a valid scale label. `mode` is WEIGHT (value in kg, 3 decimals) or PRICE (rupees). */
export function readScale(barcode: string, mode: string): ScaleReading | null {
  const b = barcode.trim();
  if (!/^2\d{12}$/.test(b) || checkDigit(b.slice(0, 12)) !== b.charCodeAt(12) - 48) return null;
  const raw = Number(b.slice(7, 12));
  const value = mode === "PRICE" ? `${raw}.00` : `${Math.floor(raw / 1000)}.${String(raw % 1000).padStart(3, "0")}`;
  return { itemCode: b.slice(2, 7), value };
}
