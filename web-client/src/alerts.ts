export interface FeedAlert {
  id: string;
  timestamp: string;
  severity: string;
  attackType: string;
  sourceIp: string;
  targetIp: string;
  detectorName: string;
  description: string;
  simulated: boolean;
}

export const FEED_CAP = 500;

export function capAlerts(alerts: FeedAlert[], cap = FEED_CAP): FeedAlert[] {
  if (cap < 0) {
    return [];
  }
  if (alerts.length <= cap) {
    return alerts.slice();
  }
  return alerts.slice(alerts.length - cap);
}

export interface AlertGroup {
  key: string;
  count: number;
  latest: FeedAlert;
  alerts: FeedAlert[];
}

export function groupAlerts(alerts: FeedAlert[]): AlertGroup[] {
  const groups: AlertGroup[] = [];
  for (const alert of alerts) {
    const key = `${alert.sourceIp}|${alert.targetIp}|${alert.attackType}`;
    const current = groups[groups.length - 1];
    const time = new Date(alert.timestamp).getTime();
    const previous = current ? new Date(current.latest.timestamp).getTime() : 0;
    if (current && current.key === key && (Number.isNaN(time) || Number.isNaN(previous) || Math.abs(time - previous) <= 30000)) {
      current.alerts.push(alert);
      current.count += 1;
      current.latest = alert;
    } else {
      groups.push({ key, count: 1, latest: alert, alerts: [alert] });
    }
  }
  return groups;
}
