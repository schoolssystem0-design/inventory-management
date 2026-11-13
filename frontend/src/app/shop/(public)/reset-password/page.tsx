"use client";

import { useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { PasswordForm } from "@/components/password-form";

export default function ResetPasswordPage() {
  return (
    <Suspense>
      <Reset />
    </Suspense>
  );
}

function Reset() {
  const token = useSearchParams().get("token") ?? "";
  return (
    <PasswordForm
      token={token}
      endpoint="/auth/password-reset/confirm"
      title="Choose a new password"
      subtitle="The link works once"
      done="Your password has been changed. Sign in with the new password."
    />
  );
}
