import { FX_API_URL, forward } from "@/lib/server";

type Ctx = { params: Promise<{ path: string[] }> };

/** Browser → fx-api proxy (adds the bearer token from the httpOnly cookie). */
export async function GET(request: Request, { params }: Ctx) {
  return forward(request, FX_API_URL, (await params).path);
}

export async function POST(request: Request, { params }: Ctx) {
  return forward(request, FX_API_URL, (await params).path);
}
