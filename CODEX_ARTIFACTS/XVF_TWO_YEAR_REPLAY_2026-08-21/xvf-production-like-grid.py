#!/usr/bin/env python3
"""Temporary fixed-allocation grid over the production-like XVF replay."""
import importlib.util
from datetime import date
from pathlib import Path

ARTIFACT_DIR = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "xvf_sim", ARTIFACT_DIR / "xvf-production-like-sim.py"
)
SIM = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SIM)
CANDIDATES, FUNDING = SIM.load_inputs()

PERIODS = (
    (date(2024, 8, 21), date(2025, 8, 21)),
    (date(2025, 8, 21), date(2026, 8, 21)),
)


def run(allocation):
    out = []
    for start, end in PERIODS:
        SIM.START = start
        SIM.END_EXCL = end
        out.append(SIM.simulate(CANDIDATES, FUNDING, allocation, True, True, False))
    return out


rows = []
step = 225.0  # five percent of USD 4,500
for b_units in range(2, 17):
    for y_units in range(2, 17 - b_units):
        h_units = 20 - b_units - y_units
        if h_units < 2:
            continue
        allocation = {
            "binance": b_units * step,
            "bybit": y_units * step,
            "hyperliquid": h_units * step,
        }
        a, b = run(allocation)
        rows.append((allocation, a, b))


def show(title, key):
    print("\n" + title)
    print("B% Y% H% | prior% recent% sum% min% | avgpos prior/recent | skips prior/recent")
    for allocation, prior, recent in sorted(rows, key=key, reverse=True)[:15]:
        shares = [round(allocation[v] / 45.0) for v in SIM.VENUES]
        print(f"{shares[0]:2d} {shares[1]:2d} {shares[2]:2d} | "
              f"{prior['return_pct']:6.2f} {recent['return_pct']:7.2f} "
              f"{prior['return_pct']+recent['return_pct']:5.2f} "
              f"{min(prior['return_pct'],recent['return_pct']):5.2f} | "
              f"{prior['avg_positions']:5.2f}/{recent['avg_positions']:5.2f} | "
              f"{prior['skipped']:4d}/{recent['skipped']:4d}")


show("Best combined two one-year returns", lambda r: r[1]["return_pct"] + r[2]["return_pct"])
show("Best worst-year return", lambda r: min(r[1]["return_pct"], r[2]["return_pct"]))
show("Best prior year", lambda r: r[1]["return_pct"])
show("Best recent year", lambda r: r[2]["return_pct"])

print("\nNamed splits")
for shares in ((1/3, 1/3, 1/3), (.40, .35, .25), (.45, .35, .20), (.45, .40, .15), (.50, .35, .15), (.35, .30, .35)):
    allocation = dict(zip(SIM.VENUES, (4500 * x for x in shares)))
    prior, recent = run(allocation)
    print(f"{tuple(round(x*100,1) for x in shares)} prior {prior['return_pct']:.2f}% "
          f"recent {recent['return_pct']:.2f}% avgpos {prior['avg_positions']:.2f}/{recent['avg_positions']:.2f} "
          f"skips {prior['skipped']}/{recent['skipped']}")
