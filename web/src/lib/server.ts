import "server-only";
import { cookies } from "next/headers";

export const TOKEN_COOKIE = "afx_token";

export const FX_API_URL = process.env.FX_API_URL ?? "http://localhost:8080";
export const AGENT_API_URL = process.env.AGENT_API_URL ?? "http://localhost:8000";

export async function sessionToken(): Promise<string | undefined> {
  return (await cookies()).get(TOKEN_COOKIE)?.value;
}

const FORWARDED_REQUEST_HEADERS = ["content-type", "idempotency-key", "x-correlation-id"];
const FORWARDED_RESPONSE_HEADERS = ["content-type", "x-correlation-id", "idempotent-replay", "retry-after"];

/**
 * Forwards a browser request to an upstream service, attaching the bearer token from the httpOnly cookie.
 * The browser never sees the token, so XSS cannot exfiltrate it.
 */
export async function forward(request: Request, upstream: string, path: string[]): Promise<Response> {
  const token = await sessionToken();
  if (!token) {
    return Response.json({ code: "UNAUTHENTICATED", detail: "Sign in again." }, { status: 401 });
  }
  const safePath = path.map(encodeURIComponent).join("/");
  const search = new URL(request.url).search;
  const headers = new Headers({ Authorization: `Bearer ${token}` });
  for (const name of FORWARDED_REQUEST_HEADERS) {
    const value = request.headers.get(name);
    if (value) headers.set(name, value);
  }
  const init: RequestInit = { method: request.method, headers, cache: "no-store" };
  if (request.method !== "GET" && request.method !== "HEAD") {
    init.body = await request.text();
  }
  let response: Response;
  try {
    response = await fetch(`${upstream}/${safePath}${search}`, init);
  } catch {
    return Response.json({ code: "UPSTREAM_UNAVAILABLE", detail: "The service is not reachable." }, { status: 502 });
  }
  const out = new Headers();
  for (const name of FORWARDED_RESPONSE_HEADERS) {
    const value = response.headers.get(name);
    if (value) out.set(name, value);
  }
  return new Response(response.body, { status: response.status, headers: out });
}
