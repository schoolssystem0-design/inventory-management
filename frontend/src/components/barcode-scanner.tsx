"use client";

import { Camera, X } from "lucide-react";
import * as React from "react";
import { Button } from "@/components/ui/button";

/**
 * Reads a barcode with the phone or laptop camera (HW-01). Uses the browser's own BarcodeDetector where it exists and
 * the ZXing WebAssembly reader (served from /zxing_reader.wasm) elsewhere. Needs HTTPS (or localhost).
 * USB and Bluetooth scanners need nothing: they type into whichever field has focus.
 */
export function BarcodeScanner({ onDetected, onClose }: { onDetected: (code: string) => void; onClose: () => void }) {
  const video = React.useRef<HTMLVideoElement>(null);
  const [error, setError] = React.useState<string | null>(null);

  React.useEffect(() => {
    let stream: MediaStream | null = null;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    async function start() {
      if (!navigator.mediaDevices?.getUserMedia) {
        setError("This browser cannot use the camera here. Use a USB scanner or type the code.");
        return;
      }
      try {
        const { BarcodeDetector, setZXingModuleOverrides } = await import("barcode-detector/ponyfill");
        setZXingModuleOverrides({
          locateFile: (path: string, prefix: string) => (path.endsWith(".wasm") ? "/zxing_reader.wasm" : prefix + path),
        });
        const detector = new BarcodeDetector({
          formats: ["ean_13", "ean_8", "upc_a", "upc_e", "code_128", "code_39", "qr_code", "itf"],
        });
        stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" }, audio: false });
        if (stopped || !video.current) return;
        video.current.srcObject = stream;
        await video.current.play();
        const scan = async () => {
          if (stopped || !video.current) return;
          try {
            const found = await detector.detect(video.current);
            if (found.length > 0 && found[0].rawValue) {
              navigator.vibrate?.(60);
              onDetected(found[0].rawValue);
              return;
            }
          } catch {
            // A frame that cannot be read: try the next one.
          }
          timer = setTimeout(scan, 180);
        };
        scan();
      } catch (e) {
        setError(
          e instanceof DOMException && e.name === "NotAllowedError"
            ? "Camera permission was refused. Allow the camera in the browser settings."
            : "The camera could not be started. Use a USB scanner or type the code.",
        );
      }
    }
    start();
    return () => {
      stopped = true;
      if (timer) clearTimeout(timer);
      stream?.getTracks().forEach((t) => t.stop());
    };
  }, [onDetected]);

  return (
    <div className="fixed inset-0 z-[60] flex flex-col bg-black" role="dialog" aria-modal="true" aria-label="Scan a barcode">
      <div className="flex items-center justify-between p-3">
        <p className="text-sm font-semibold">Point the camera at the barcode</p>
        <button className="rounded-lg p-2 hover:bg-white/10" onClick={onClose} aria-label="Close scanner">
          <X className="size-5" />
        </button>
      </div>
      <div className="relative flex-1">
        <video ref={video} className="absolute inset-0 size-full object-cover" playsInline muted />
        <div className="pointer-events-none absolute inset-x-8 top-1/2 h-40 -translate-y-1/2 rounded-2xl border-2 border-aqua/80" />
      </div>
      {error && <p className="bg-bad/20 p-4 text-center text-sm text-bad">{error}</p>}
    </div>
  );
}

/** A small "scan with camera" button that opens the scanner and hands back the code. */
export function ScanButton({ onCode, label = "Scan" }: { onCode: (code: string) => void; label?: string }) {
  const [open, setOpen] = React.useState(false);
  const handle = React.useCallback(
    (code: string) => {
      setOpen(false);
      onCode(code);
    },
    [onCode],
  );
  return (
    <>
      <Button variant="secondary" onClick={() => setOpen(true)} aria-label="Scan barcode with camera">
        <Camera />
        <span className="hidden sm:inline">{label}</span>
      </Button>
      {open && <BarcodeScanner onDetected={handle} onClose={() => setOpen(false)} />}
    </>
  );
}
