import { type NextRequest } from "next/server";

/**
 * Same-origin proxy to the Spring Boot API (as in the school system). The browser only talks to its own host, so the
 * session cookie stays host-only per shop subdomain and no CORS is needed. API_PROXY_TARGET is the API origin
 * (Railway private or public URL); locally it defaults to http://localhost:8080.
 */
function backendOrigin(): string {
  return (process.env.API_PROXY_TARGET?.trim() || "http://localhost:8080").replace(/\/$/, "");
}

const FORWARD_REQUEST_HEADERS = [
  "accept",
  "content-type",
  "cookie",
  "x-xsrf-token",
  "idempotency-key",
  "x-request-id",
  "x-approval-user",
  "x-approval-pin",
  "if-none-match",
  "user-agent",
];

async function proxy(request: NextRequest, path: string[]): Promise<Response> {
  const target = `${backendOrigin()}/api/v1/${path.map(encodeURIComponent).join("/")}${request.nextUrl.search}`;
  const headers = new Headers();
  for (const name of FORWARD_REQUEST_HEADERS) {
    const value = request.headers.get(name);
    if (value) headers.set(name, value);
  }
  const forwardedFor = request.headers.get("x-forwarded-for");
  if (forwardedFor) headers.set("x-forwarded-for", forwardedFor);

  const method = request.method;
  const init: RequestInit = { method, headers, redirect: "manual", cache: "no-store" };
  if (method !== "GET" && method !== "HEAD") {
    // Buffered, not streamed: with a streamed body, fetch turns any 401 reply (wrong password, code required) into a
    // network error (Fetch spec, credential retry rule), so sign-in errors would look like "server cannot be reached".
    init.body = await request.arrayBuffer();
  }

  let upstream: Response;
  try {
    upstream = await fetch(target, init);
  } catch (error) {
    console.error(`API proxy: ${method} ${target} failed`, error);
    return Response.json(
      { code: "api_unreachable", detail: "The server cannot be reached. Check the connection and try again." },
      { status: 503 },
    );
  }
  const responseHeaders = new Headers();
  upstream.headers.forEach((value, key) => {
    if (key.toLowerCase() !== "set-cookie") responseHeaders.set(key, value);
  });
  // Several Set-Cookie values must be forwarded separately; merging them breaks the session.
  for (const cookie of upstream.headers.getSetCookie()) {
    responseHeaders.append("Set-Cookie", cookie);
  }
  for (const hop of ["connection", "keep-alive", "transfer-encoding", "content-encoding", "content-length"]) {
    responseHeaders.delete(hop);
  }
  return new Response(upstream.body, { status: upstream.status, statusText: upstream.statusText, headers: responseHeaders });
}

type Context = { params: Promise<{ path: string[] }> };

async function handle(request: NextRequest, context: Context) {
  const { path } = await context.params;
  return proxy(request, path);
}

export const GET = handle;
export const POST = handle;
export const PUT = handle;
export const PATCH = handle;
export const DELETE = handle;
