import { create } from "zustand";
import { capAlerts, type FeedAlert } from "./alerts";
import { sessionAfterStatus, type SessionUser } from "./session";
import type { Asset, NetLink, SocTeam } from "./types";

export type AuthState = "unknown" | "in" | "out";

interface AppState {
  auth: AuthState;
  user: SessionUser | null;
  running: boolean;
  autoMode: boolean;
  alertCount: number;
  assets: Asset[];
  links: NetLink[];
  alerts: FeedAlert[];
  selectedId: string | null;
  disconnected: boolean;
  notice: string;
  teams: SocTeam[];
  plannerView: { excluded: string[]; unreachable: string[]; pairs: { source: string; target: string; id: string }[]; hover: string | null; show: boolean } | null;
  riskHistory: Record<string, number[]>;
  attackToken: number;
  attack: { src: string; dst: string } | null;
  cue: { id: string; sourceIp: string; targetIp: string; severity: string } | null;
  holdId: string | null;
  setUser: (user: SessionUser) => void;
  markSignedOut: () => void;
  applyHttpStatus: (status: number) => void;
  setSimulation: (running: boolean, autoMode: boolean, alertCount: number) => void;
  setNetwork: (assets: Asset[], links: NetLink[], teams?: SocTeam[]) => void;
  setPlannerView: (view: AppState["plannerView"]) => void;
  pushAlert: (alert: FeedAlert) => void;
  setAlerts: (alerts: FeedAlert[]) => void;
  setSelectedId: (id: string | null) => void;
  setDisconnected: (disconnected: boolean) => void;
  setNotice: (notice: string) => void;
  setAttack: (src: string, dst: string) => void;
  setHoldId: (holdId: string | null) => void;
}

export const useApp = create<AppState>((set) => ({
  auth: "unknown",
  user: null,
  running: false,
  autoMode: false,
  alertCount: 0,
  assets: [],
  links: [],
  alerts: [],
  selectedId: null,
  disconnected: false,
  notice: "",
  teams: [],
  plannerView: null,
  riskHistory: {},
  attackToken: 0,
  attack: null,
  cue: null,
  holdId: null,
  setUser: (user) => set({ auth: "in", user, notice: "" }),
  markSignedOut: () => set({ auth: "out", user: null }),
  applyHttpStatus: (status) =>
    set((state) => {
      const user = sessionAfterStatus(state.user, status);
      if (status === 401) {
        return { auth: "out", user: null };
      }
      return { user };
    }),
  setSimulation: (running, autoMode, alertCount) => set({ running, autoMode, alertCount }),
  setNetwork: (assets, links, teams) =>
    set((state) => {
      const riskHistory = { ...state.riskHistory };
      for (const asset of assets) {
        const previous = riskHistory[asset.id] ?? [];
        const last = previous[previous.length - 1];
        if (last !== asset.riskScore) {
          riskHistory[asset.id] = [...previous, asset.riskScore].slice(-40);
        }
      }
      return { assets, links, riskHistory, teams: teams ?? state.teams };
    }),
  setPlannerView: (plannerView) => set({ plannerView }),
  pushAlert: (alert) =>
    set((state) => {
      const index = state.alerts.findIndex((row) => row.id === alert.id);
      if (index >= 0) {
        const alerts = state.alerts.map((row, at) => (at === index ? { ...row, ...alert } : row));
        return { alerts };
      }
      return {
        alerts: capAlerts([...state.alerts, alert]),
        cue: {
          id: alert.id,
          sourceIp: alert.sourceIp,
          targetIp: alert.targetIp,
          severity: alert.severity,
        },
      };
    }),
  setAlerts: (alerts) => set({ alerts: capAlerts(alerts) }),
  setSelectedId: (selectedId) => set({ selectedId }),
  setDisconnected: (disconnected) => set({ disconnected }),
  setNotice: (notice) => set({ notice }),
  setAttack: (src, dst) => set((state) => ({ attack: { src, dst }, attackToken: state.attackToken + 1 })),
  setHoldId: (holdId) => set({ holdId }),
}));
