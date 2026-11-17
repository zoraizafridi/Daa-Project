import { lazy, Suspense, useEffect, useRef, useState, type ReactNode } from "react";
import { BrowserRouter, Link, Navigate, Route, Routes, useLocation, type Location } from "react-router-dom";
import { gsap } from "gsap";
import { ENTER_MS, LEAVE_MS, STAGGER_S } from "./scene";
import { Landing } from "./pages/Landing";
import { Login } from "./pages/Login";
import { useReducedMotion } from "./useReducedMotion";
import { canUseDaa, accessDenied } from "./permissions";
import { useApp } from "./store";
import { Page } from "./components/ui";

const Shell = lazy(() => import("./pages/Shell").then((module) => ({ default: module.Shell })));
const Overview = lazy(() => import("./pages/Overview").then((module) => ({ default: module.Overview })));
const Reconnection = lazy(() => import("./pages/Reconnection").then((module) => ({ default: module.Reconnection })));
const Mitigation = lazy(() => import("./pages/Mitigation").then((module) => ({ default: module.Mitigation })));
const Pipeline = lazy(() => import("./pages/Pipeline").then((module) => ({ default: module.Pipeline })));

function AdminRoute({ children }: { children: ReactNode }) {
  const auth = useApp((state) => state.auth);
  const role = useApp((state) => state.user?.role ?? "");
  if (auth === "out") return <Navigate to="/login" replace />;
  if (auth !== "in") return <p role="status">Checking session.</p>;
  if (!canUseDaa(role)) return <Page title="Access denied" description={accessDenied(role)}><p>Only Admin can use this algorithm workspace. The server also enforces Admin authorization.</p><Link to="/portal">Back to overview</Link></Page>;
  return children;
}

function RouteStage() {
  const location = useLocation();
  const reduced = useReducedMotion();
  const panel = useRef<HTMLDivElement>(null);
  const [shown, setShown] = useState<Location>(location);
  const first = useRef(true);

  useEffect(() => {
    if (shown.pathname === location.pathname && shown.key === location.key) return;
    const insidePortal = shown.pathname.startsWith("/portal") && location.pathname.startsWith("/portal");
    if (insidePortal) {
      setShown(location);
      return;
    }
    const el = panel.current;
    if (!el || reduced || first.current) {
      first.current = false;
      gsap.set(el, { y: 0, autoAlpha: 1 });
      setShown(location);
      return;
    }
    const tween = gsap.to(el, {
      y: -8,
      autoAlpha: 0,
      duration: LEAVE_MS / 1000,
      ease: "power3.out",
      onComplete: () => setShown(location),
    });
    return () => { tween.kill(); };
  }, [location, reduced, shown.key, shown.pathname]);

  useEffect(() => {
    const el = panel.current;
    if (!el || first.current) return;
    if (reduced) {
      gsap.set(el, { y: 0, autoAlpha: 1 });
      return;
    }
    const bits = el.querySelectorAll("[data-stagger]");
    const enter = gsap.fromTo(
      el,
      { y: 8, autoAlpha: 0 },
      { y: 0, autoAlpha: 1, duration: ENTER_MS / 1000, ease: "power3.out" },
    );
    const stagger = bits.length
      ? gsap.fromTo(
          bits,
          { y: 8, autoAlpha: 0 },
          { y: 0, autoAlpha: 1, duration: ENTER_MS / 1000, stagger: STAGGER_S, ease: "power3.out" },
        )
      : null;
    return () => {
      enter.kill();
      stagger?.kill();
    };
  }, [shown.key, reduced]);

  return (
    <div className="app-stage">
      <div ref={panel} className="route-stage">
        <Suspense fallback={<p className="route-loading">Loading</p>}>
          <AppRoutes location={shown} />
        </Suspense>
      </div>
    </div>
  );
}

function PortalNotFound() {
  return (
    <section className="page">
      <header className="page-head">
        <h1>Not found</h1>
        <p>That portal route does not exist.</p>
      </header>
      <p><Link to="/portal">Back to overview</Link></p>
    </section>
  );
}

export function AppRoutes({ location }: { location?: Location }) {
  return (
    <Routes location={location}>
      <Route path="/" element={<Landing />} />
      <Route path="/login" element={<Login />} />
      <Route path="/portal" element={<Shell />}>
        <Route index element={<Overview />} />
        <Route path="overview" element={<Navigate to="/portal" replace />} />
        <Route path="reconnection" element={<AdminRoute><Reconnection /></AdminRoute>} />
        <Route path="mitigation" element={<AdminRoute><Mitigation /></AdminRoute>} />
        <Route path="pipeline" element={<AdminRoute><Pipeline /></AdminRoute>} />
        <Route path="*" element={<PortalNotFound />} />
      </Route>
      <Route path="*" element={<PortalNotFound />} />
    </Routes>
  );
}

export function AppTree() {
  return <RouteStage />;
}

export function App() {
  return (
    <BrowserRouter>
      <AppTree />
    </BrowserRouter>
  );
}
