export function canLaunchAttack(role: string): boolean {
  return role === "Admin" || role === "Analyst";
}

export function canControlSimulation(role: string): boolean {
  return canLaunchAttack(role);
}

export interface NavEntry {
  label: string;
  to: string;
}

export function canUseDaa(role: string): boolean {
  return role === "Admin";
}

export function navEntries(role: string): NavEntry[] {
  const overview = { label: "Overview", to: "/portal" };
  return canUseDaa(role) ? [
    overview,
    { label: "Reconnection", to: "/portal/reconnection" },
    { label: "Mitigation", to: "/portal/mitigation" },
    { label: "Pipeline", to: "/portal/pipeline" },
  ] : [overview];
}

export function accessDenied(role: string): string {
  return `Access denied for role ${role}`;
}
