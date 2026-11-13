import { cn } from "cn";
import * as React from "react";

/** A labelled form field with an optional hint and error, wired for screen readers. */
export function Field({
  label,
  hint,
  error,
  children,
  className,
  required,
}: {
  label: string;
  hint?: React.ReactNode;
  error?: string;
  children: React.ReactElement<{ id?: string; "aria-invalid"?: boolean; "aria-describedby"?: string }>;
  className?: string;
  required?: boolean;
}) {
  const id = React.useId();
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined;
  return (
    <div className={cn("space-y-1.5", className)}>
      <label htmlFor={id} className="block text-[13px] font-medium text-muted">
        {label}
        {required && <span className="text-bad"> *</span>}
      </label>
      {React.cloneElement(children, {
        id,
        "aria-invalid": error ? true : undefined,
        "aria-describedby": describedBy,
      })}
      {error ? (
        <p id={`${id}-error`} className="text-xs text-bad">
          {error}
        </p>
      ) : hint ? (
        <p id={`${id}-hint`} className="text-xs text-faint">
          {hint}
        </p>
      ) : null}
    </div>
  );
}

export const Input = React.forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(
  function Input({ className, ...props }, ref) {
    return <input ref={ref} className={cn("field", className)} {...props} />;
  },
);

export const Textarea = React.forwardRef<HTMLTextAreaElement, React.TextareaHTMLAttributes<HTMLTextAreaElement>>(
  function Textarea({ className, ...props }, ref) {
    return <textarea ref={ref} className={cn("field min-h-20", className)} {...props} />;
  },
);

export const Select = React.forwardRef<HTMLSelectElement, React.SelectHTMLAttributes<HTMLSelectElement>>(
  function Select({ className, children, ...props }, ref) {
    return (
      <select ref={ref} className={cn("field appearance-none bg-[length:16px] pr-9", className)} {...props}>
        {children}
      </select>
    );
  },
);

/** A money or quantity input: numeric keyboard on phones, right-aligned figures. */
export const AmountInput = React.forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(
  function AmountInput({ className, ...props }, ref) {
    return <input ref={ref} inputMode="decimal" className={cn("field text-right font-mono", className)} {...props} />;
  },
);

export function Checkbox({
  label,
  hint,
  className,
  ...props
}: React.InputHTMLAttributes<HTMLInputElement> & { label: React.ReactNode; hint?: string }) {
  return (
    <label className={cn("flex cursor-pointer items-start gap-3 rounded-lg py-1", className)}>
      <input type="checkbox" className="mt-0.5 size-4 shrink-0 accent-[#4fbfae]" {...props} />
      <span>
        <span className="block text-sm text-ink">{label}</span>
        {hint && <span className="block text-xs text-faint">{hint}</span>}
      </span>
    </label>
  );
}

/** An on/off switch (a checkbox styled as a switch, so keyboard and screen readers work). */
export function Switch({
  checked,
  onChange,
  label,
  disabled,
}: {
  checked: boolean;
  onChange: (value: boolean) => void;
  label: string;
  disabled?: boolean;
}) {
  return (
    <label className={cn("inline-flex items-center", disabled ? "cursor-not-allowed opacity-50" : "cursor-pointer")}>
      <input
        type="checkbox"
        role="switch"
        className="peer sr-only"
        checked={checked}
        disabled={disabled}
        aria-label={label}
        onChange={(e) => onChange(e.target.checked)}
      />
      <span className="relative h-6 w-10 rounded-full bg-raised shadow-[inset_0_0_0_1px_var(--color-line-strong)] transition-colors peer-checked:bg-aqua peer-focus-visible:outline-2 peer-focus-visible:outline-aqua after:absolute after:top-1 after:left-1 after:size-4 after:rounded-full after:bg-ink after:transition-transform peer-checked:after:translate-x-4 peer-checked:after:bg-on-aqua" />
    </label>
  );
}

/** Shows the server's message for a field (from ApiError.fieldErrors). */
export function fieldError(error: unknown, name: string): string | undefined {
  if (error && typeof error === "object" && "field" in error && typeof (error as { field: unknown }).field === "function") {
    return (error as { field: (n: string) => string | undefined }).field(name);
  }
  return undefined;
}
