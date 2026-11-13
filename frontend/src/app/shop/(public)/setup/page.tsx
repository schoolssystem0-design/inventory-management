"use client";

import { useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { PasswordForm } from "@/components/password-form";

/** Completes an account setup link: the new user (or shop owner) chooses a password. */
export default function SetupPage() {
  return (
    <Suspense>
      <Setup />
    </Suspense>
  );
}

function Setup() {
  const token = useSearchParams().get("token") ?? "";
  return (
    <PasswordForm
      token={token}
      endpoint="/auth/setup"
      title="Set up your account"
      subtitle="Choose a password to finish setting up"
      done="Your account is ready. Sign in with your username and new password."
    />
  );
}
