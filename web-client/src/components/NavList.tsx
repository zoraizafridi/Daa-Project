import { useLayoutEffect, useRef, useState } from "react";
import { NavLink, useLocation } from "react-router-dom";
import { navEntries } from "../permissions";

const stroke = { fill: "none", stroke: "currentColor", strokeWidth: 1.5, strokeLinecap: "round" as const, strokeLinejoin: "round" as const };

export function NavList({ role }: { role: string }) {
  const location = useLocation();
  const navRef = useRef<HTMLElement>(null);
  const [marker, setMarker] = useState({ top: 0, show: false });
  const entries = navEntries(role);

  useLayoutEffect(() => {
    const nav = navRef.current;
    const active = nav?.querySelector<HTMLElement>("a[aria-current='page']");
    if (!nav || !active) {
      setMarker({ top: 0, show: false });
      return;
    }
    const navBox = nav.getBoundingClientRect();
    const box = active.getBoundingClientRect();
    setMarker({ top: box.top - navBox.top + nav.scrollTop, show: true });
  }, [location.pathname, role]);

  return (
    <nav ref={navRef} aria-label="Portal">
      <span className={marker.show ? "nav-marker" : "nav-marker is-off"} style={{ top: marker.top }} />
      <div className="nav-block">
        <p className="nav-group">Monitoring</p>
        <span className="nav-divider" />
        {entries.map((entry) => (
          <NavLink key={entry.to} to={entry.to} end data-label={entry.label}>
            <svg className="nav-ico" viewBox="0 0 24 24" aria-hidden="true">
              <path d={entry.to === "/portal" ? "M4 10.5 12 4l8 6.5V20H4Z" : "M5 5h5v5H5ZM14 14h5v5h-5ZM10 7h7v7M7 10v7h7"} {...stroke} />
            </svg>
            <span className="nav-label">{entry.label}</span>
          </NavLink>
        ))}
      </div>
    </nav>
  );
}
