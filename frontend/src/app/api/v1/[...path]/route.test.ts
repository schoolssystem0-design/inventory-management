// @vitest-environment node
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { NextRequest } from "next/server";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { GET, POST } from "./route";

let server: Server;
let seen: { method?: string; body?: string; headers?: Record<string, unknown> } = {};

beforeAll(async () => {
  server = createServer((req, res) => {
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", () => {
      seen = { method: req.method, body, headers: req.headers };
      if (req.url?.startsWith("/api/v1/auth/login")) {
        res.writeHead(401, { "content-type": "application/problem+json" });
        res.end(JSON.stringify({ code: "invalid_credentials", detail: "Username or password is incorrect" }));
        return;
      }
      res.writeHead(200, { "content-type": "application/json", "set-cookie": ["A=1; Path=/", "B=2; Path=/"] });
      res.end(JSON.stringify({ ok: true, url: req.url }));
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  process.env.API_PROXY_TARGET = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});

afterAll(() => {
  server.closeAllConnections();
  server.close();
});

const ctx = (path: string[]) => ({ params: Promise.resolve({ path }) });

describe("API proxy", () => {
  it("passes a 401 on a POST through instead of reporting the server as unreachable", async () => {
    const req = new NextRequest("http://demo.localhost:3000/api/v1/auth/login", {
      method: "POST",
      headers: { "content-type": "application/json", "x-xsrf-token": "t" },
      body: JSON.stringify({ username: "owner", password: "x" }),
    });
    const res = await POST(req, ctx(["auth", "login"]));
    expect(res.status).toBe(401);
    expect((await res.json()).code).toBe("invalid_credentials");
    expect(seen.body).toBe('{"username":"owner","password":"x"}');
    expect(seen.headers?.["x-xsrf-token"]).toBe("t");
  });

  it("keeps the query string and forwards each Set-Cookie separately", async () => {
    const res = await GET(new NextRequest("http://demo.localhost:3000/api/v1/products?q=a%20b&page=2"), ctx(["products"]));
    expect(res.status).toBe(200);
    expect((await res.json()).url).toBe("/api/v1/products?q=a%20b&page=2");
    expect(res.headers.getSetCookie()).toEqual(["A=1; Path=/", "B=2; Path=/"]);
  });

  it("reports 503 when the API is down", async () => {
    const saved = process.env.API_PROXY_TARGET;
    process.env.API_PROXY_TARGET = "http://127.0.0.1:1";
    const res = await GET(new NextRequest("http://demo.localhost:3000/api/v1/products"), ctx(["products"]));
    process.env.API_PROXY_TARGET = saved;
    expect(res.status).toBe(503);
    expect((await res.json()).code).toBe("api_unreachable");
  });
});
