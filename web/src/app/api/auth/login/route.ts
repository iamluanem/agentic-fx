import { FX_API_URL, TOKEN_COOKIE } from "@/lib/server";

export async function POST(request: Request) {
  const body = await request.json().catch(() => null);
  if (!body || typeof body.email !== "string" || typeof body.password !== "string") {
    return Response.json({ code: "BAD_REQUEST", detail: "Email and password are required." }, { status: 400 });
  }
  let upstream: Response;
  try {
    upstream = await fetch(`${FX_API_URL}/auth/token`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ email: body.email, password: body.password }),
      cache: "no-store",
    });
  } catch {
    return Response.json({ code: "UPSTREAM_UNAVAILABLE", detail: "fx-api is not reachable." }, { status: 502 });
  }
  if (!upstream.ok) {
    return Response.json({ code: "INVALID_CREDENTIALS", detail: "Email or password is incorrect." }, { status: 401 });
  }
  const token = (await upstream.json()) as { accessToken: string; expiresAt: string };
  const response = Response.json({ ok: true });
  const maxAge = Math.max(60, Math.floor((Date.parse(token.expiresAt) - Date.now()) / 1000));
  const secure = process.env.COOKIE_SECURE === "true" ? "; Secure" : "";
  response.headers.append(
    "Set-Cookie",
    `${TOKEN_COOKIE}=${token.accessToken}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${maxAge}${secure}`,
  );
  return response;
}
