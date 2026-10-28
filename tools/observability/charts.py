"""Standalone local research figures from reconciled native observations."""

import re
import textwrap


def plotting():
    try:
        import matplotlib
    except ImportError as error:
        raise RuntimeError(
            "Charts require tools/observability/requirements.txt in the selected Python environment"
        ) from error
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    plt.rcParams.update(
        {
            "font.family": "DejaVu Sans",
            "font.size": 10,
            "axes.spines.top": False,
            "axes.spines.right": False,
        }
    )
    return plt


def safe_name(value):
    return re.sub(r"[^a-zA-Z0-9_-]", "-", value)[:80]


def inspect_charts(report, output):
    plt = plotting()
    paths = []
    for scenario in report["scenarios"]:
        fig, axes = plt.subplots(2, 1, figsize=(11, 6), constrained_layout=True)
        for ax, side, title, threshold in zip(
            axes,
            ["server", "client"],
            ["Complete server ticks", "Client frame intervals"],
            [50, 16.6667],
        ):
            data = scenario[side]
            rows = data["rows"]
            if rows:
                origin = rows[0]["startEpochNanos"]
                ax.plot(
                    [(r["endEpochNanos"] - origin) / 1e9 for r in rows],
                    [r["durationNanos"] / 1e6 for r in rows],
                    color="#167e77",
                    linewidth=0.9,
                )
                ax.axhline(
                    threshold,
                    color="#ad5b43",
                    linestyle="--",
                    linewidth=0.8,
                    label=f"{threshold:g} ms reference",
                )
                ax.set_ylim(bottom=0)
                ax.set_xlabel("Seconds since this process’s first captured sample")
                ax.legend(frameon=False, loc="upper right")
            else:
                ax.text(
                    0.5,
                    0.5,
                    "Unavailable: " + data.get("unavailable", "no samples"),
                    transform=ax.transAxes,
                    ha="center",
                    va="center",
                    wrap=True,
                )
            ax.set_title(title, loc="left")
            ax.set_ylabel("Milliseconds")
            ax.grid(axis="y", alpha=0.15)
        fig.suptitle(
            scenario["scenario"] + " — native timing observations", fontsize=14, weight="bold"
        )
        fig.supxlabel(
            report["mode"]
            + " capture · frame intervals include cap/scheduling · physical presentation not measured",
            fontsize=9,
        )
        path = output / (safe_name(scenario["scenario"]) + "-timing.png")
        fig.savefig(path, dpi=150)
        plt.close(fig)
        paths.append(path.name)
    return paths


def trace_charts(trace, output):
    plt = plotting()
    paths = []
    for side, process in trace["processes"].items():
        for scenario, data in process.get("scenarios", {}).items():
            rows = data["executionSamples"][:12]
            if not rows:
                continue
            fig, ax = plt.subplots(figsize=(12, 8), constrained_layout=True)
            labels = [
                name.split(" | ", 1)[0] + "\n" + textwrap.fill(name.split(" | ", 1)[-1], width=54)
                for name, _ in rows
            ]
            ax.barh(labels[::-1], [value for _, value in rows][::-1], color="#167e77")
            ax.tick_params(axis="y", labelsize=8)
            ax.set_xlabel("Java execution samples (counts, not CPU duration)")
            ax.set_title(
                side.capitalize() + " sampled work — " + scenario,
                loc="left",
                fontsize=14,
                weight="bold",
            )
            ax.grid(axis="x", alpha=0.15)
            path = output / (safe_name(scenario) + "-" + side + "-samples.png")
            fig.savefig(path, dpi=150)
            plt.close(fig)
            paths.append(path.name)
    return paths


def comparison_charts(comparison, output):
    plt = plotting()
    paths = []
    for scenario in sorted({r["scenario"] for r in comparison["metrics"]}):
        rows = [r for r in comparison["metrics"] if r["scenario"] == scenario and r["available"]]
        fig, axes = plt.subplots(2, 3, figsize=(12, 7), constrained_layout=True)
        for ax, row in zip(axes.flat, rows):
            values = [[p[side] for p in row["pairs"]] for side in [0, 1]]
            ax.bar(
                [0, 1],
                [row["baselineMean"], row["candidateMean"]],
                color=["#687682", "#167e77"],
                width=0.6,
            )
            for side, group in enumerate(values):
                ax.scatter([side] * len(group), group, color="#15232c", s=20)
            ax.set_xticks([0, 1], ["Baseline", "Candidate"])
            titles = {
                "serverTickP95Ms": ("Server tick p95", "Milliseconds"),
                "serverTickP99Ms": ("Server tick p99", "Milliseconds"),
                "frameIntervalP99Ms": ("Frame interval p99", "Milliseconds"),
                "cubeResidencySeconds": ("All target cubes resident", "Seconds"),
                "serverQueueCpuPercentOfOneCore": ("Server DH update worker", "% of one CPU core"),
                "clientQueueCpuPercentOfOneCore": ("Client DH update worker", "% of one CPU core"),
            }
            title, unit = titles[row["metric"]]
            ax.set_title(title, fontsize=10)
            ax.set_ylabel(unit)
            ax.grid(axis="y", alpha=0.15)
        for ax in list(axes.flat)[len(rows) :]:
            ax.set_visible(False)
        fig.suptitle("Paired native measurements — " + scenario, fontsize=14, weight="bold")
        fig.supxlabel(
            "Bars: run means. Dots: independent runs. Diagnostic timings excluded; no automatic acceptance.",
            fontsize=9,
        )
        path = output / (safe_name(scenario) + "-comparison.png")
        fig.savefig(path, dpi=150)
        plt.close(fig)
        paths.append(path.name)
    return paths
