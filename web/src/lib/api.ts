"use client";

import { useCallback, useEffect, useRef, useState } from "react";

export class ApiError extends Error {
  constructor(public status: number, public code: string, detail: string) {
    super(detail);
  }
}

async function parse<T>(response: Response): Promise<T> {
  if (response.status === 401 && typeof window !== "undefined") {
    window.location.href = "/login";
  }
  const text = await response.text();
  const body = text ? JSON.parse(text) : null;
  if (!response.ok) {
    throw new ApiError(response.status, body?.code ?? "HTTP_ERROR", body?.detail ?? response.statusText);
  }
  return body as T;
}

export async function fxGet<T>(path: string): Promise<T> {
  return parse<T>(await fetch(`/api/fx${path}`, { cache: "no-store" }));
}

/** POST with an Idempotency-Key: pass the same key when retrying the same user action. */
export async function fxPost<T>(path: string, body?: unknown, idempotencyKey?: string): Promise<T> {
  const headers: Record<string, string> = { "content-type": "application/json" };
  if (idempotencyKey) headers["Idempotency-Key"] = idempotencyKey;
  return parse<T>(
    await fetch(`/api/fx${path}`, { method: "POST", headers, body: body === undefined ? undefined : JSON.stringify(body) }),
  );
}

export async function agentPost<T>(path: string, body: unknown): Promise<T> {
  return parse<T>(
    await fetch(`/api/agent${path}`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
    }),
  );
}

export function newKey(): string {
  return crypto.randomUUID();
}

/** Fetches and re-fetches every {intervalMs} while the tab is visible. */
export function usePoll<T>(path: string | null, intervalMs = 3000) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const alive = useRef(true);

  const load = useCallback(async () => {
    if (!path) return;
    try {
      const result = await fxGet<T>(path);
      if (alive.current) {
        setData(result);
        setError(null);
      }
    } catch (e) {
      if (alive.current) setError(e instanceof ApiError ? e : new ApiError(0, "NETWORK", String(e)));
    }
  }, [path]);

  useEffect(() => {
    alive.current = true;
    load();
    const id = setInterval(() => {
      if (document.visibilityState === "visible") load();
    }, intervalMs);
    return () => {
      alive.current = false;
      clearInterval(id);
    };
  }, [load, intervalMs]);

  return { data, error, reload: load };
}
