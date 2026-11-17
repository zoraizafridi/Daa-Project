export interface Asset {
  id: string;
  name: string;
  ip: string;
  type: string;
  criticality: number;
  riskScore: number;
  status: string;
}

export interface NetLink {
  id: string;
  source: string;
  target: string;
  baseCost: number;
  bandwidth: number;
  status: string;
}

export interface SocTeam {
  name: string;
  capacity: number;
}
