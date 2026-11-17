import { useState, type FormEvent } from "react";
import { HttpStatusError, request } from "../api";
import { launchReady } from "../launch";
import { accessDenied, canLaunchAttack } from "../permissions";
import { useApp } from "../store";

const TYPES = [
  { id: "PORT_SCAN", label: "Port scan" },
  { id: "BRUTE_FORCE", label: "Brute force" },
  { id: "DOS_FLOOD", label: "DoS flood" },
  { id: "DATA_TRANSFER", label: "Data transfer" },
];

export function AttackControls() {
  const role = useApp((state) => state.user?.role ?? "");
  const assets = useApp((state) => state.assets);
  const selectedId = useApp((state) => state.selectedId);
  const setNotice = useApp((state) => state.setNotice);
  const [type, setType] = useState(TYPES[0].id);
  const [src, setSrc] = useState("");
  const [dst, setDst] = useState("");
  const [busy, setBusy] = useState(false);
  const ready = launchReady(src, dst) && assets.some((asset) => asset.id === src) && assets.some((asset) => asset.id === dst);

  if (!canLaunchAttack(role)) return null;

  async function launch(event: FormEvent) {
    event.preventDefault();
    if (!ready || busy || !canLaunchAttack(useApp.getState().user?.role ?? "")) return;
    setNotice("");
    setBusy(true);
    try {
      await request("/api/attacks", {
        method: "POST",
        body: JSON.stringify({ type, src, dst }),
      });
    } catch (error) {
      if (error instanceof HttpStatusError && error.status === 403) setNotice(accessDenied(role));
      else if (error instanceof HttpStatusError && error.status === 400) setNotice("Unknown host or attack type.");
      else if (!(error instanceof HttpStatusError && error.status === 401)) setNotice("The attack could not be launched.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="row" aria-label="Basic attack injector" onSubmit={launch}>
      <label>
        Type{" "}
        <select value={type} onChange={(event) => setType(event.target.value)}>
          {TYPES.map((item) => <option key={item.id} value={item.id}>{item.label}</option>)}
        </select>
      </label>
      <label>
        Source{" "}
        <select value={src} onChange={(event) => setSrc(event.target.value)}>
          <option value="">Choose host</option>
          {assets.map((asset) => <option key={asset.id} value={asset.id}>{asset.name}</option>)}
        </select>
      </label>
      <label>
        Target{" "}
        <select value={dst} onChange={(event) => setDst(event.target.value)}>
          <option value="">Choose host</option>
          {assets.map((asset) => <option key={asset.id} value={asset.id}>{asset.name}</option>)}
        </select>
      </label>
      <button type="button" className="secondary" disabled={!selectedId} onClick={() => selectedId && setSrc(selectedId)}>Use selection as source</button>
      <button type="button" className="secondary" disabled={!selectedId} onClick={() => selectedId && setDst(selectedId)}>Use selection as target</button>
      <button type="submit" className="primary" disabled={!ready || busy}>Launch</button>
    </form>
  );
}
