import type { FeedAlert } from "./alerts";
import { useApp } from "./store";
import type { Asset, NetLink, SocTeam } from "./types";

export class HttpStatusError extends Error {
  status: number;

  constructor(status: number) {
    super(String(status));
    this.status = status;
  }
}

export async function request(path: string, init?: RequestInit): Promise<Response> {
  const headers = new Headers(init?.headers);
  const method = (init?.method ?? "GET").toUpperCase();
  if (init?.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (method !== "GET" && method !== "HEAD" && !headers.has("X-CSRF-Token")) {
    const token = csrfToken();
    if (token) {
      headers.set("X-CSRF-Token", token);
    }
  }
  const response = await fetch(path, {
    ...init,
    headers,
    credentials: "same-origin",
  });
  if (response.status === 401 && path !== "/api/login") {
    useApp.getState().applyHttpStatus(401);
  }
  if (!response.ok) {
    throw new HttpStatusError(response.status);
  }
  return response;
}

function csrfToken(): string | null {
  if (typeof document === "undefined") {
    return null;
  }
  const match = document.cookie.match(/(?:^|;\s*)csrf=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : null;
}

export async function readJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await request(path, init);
  return (await response.json()) as T;
}

export interface StateBody {
  running: boolean;
  autoMode: boolean;
  user: { username: string; role: string };
  alertCount: number;
}

export function applyState(body: StateBody): void {
  const store = useApp.getState();
  if (body.user?.username) {
    store.setUser({ username: body.user.username, role: body.user.role });
  }
  store.setSimulation(body.running, body.autoMode, body.alertCount);
}

export async function loadState(): Promise<StateBody> {
  const body = await readJson<StateBody>("/api/state");
  applyState(body);
  return body;
}

export interface NetworkBody {
  assets: Asset[];
  links: NetLink[];
  teams: SocTeam[];
}

export async function loadNetwork(): Promise<void> {
  const body = await readJson<NetworkBody>("/api/network");
  useApp.getState().setNetwork(body.assets, body.links, body.teams);
}

export async function loadAlerts(): Promise<void> {
  const body = await readJson<FeedAlert[]>("/api/alerts");
  useApp.getState().setAlerts(body);
}
