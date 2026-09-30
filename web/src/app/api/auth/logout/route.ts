import { TOKEN_COOKIE } from "@/lib/server";

export async function POST() {
  const response = Response.json({ ok: true });
  response.headers.append("Set-Cookie", `${TOKEN_COOKIE}=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0`);
  return response;
}
