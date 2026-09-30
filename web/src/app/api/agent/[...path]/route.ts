import { AGENT_API_URL, forward } from "@/lib/server";

type Ctx = { params: Promise<{ path: string[] }> };

/** Browser → agent-service proxy. Only the agent endpoints are reachable. */
export async function POST(request: Request, { params }: Ctx) {
  const path = (await params).path;
  if (path[0] !== "agent") {
    return Response.json({ code: "NOT_FOUND" }, { status: 404 });
  }
  return forward(request, AGENT_API_URL, path);
}
