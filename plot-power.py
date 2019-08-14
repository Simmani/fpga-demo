#!/usr/bin/env python3

# See LICENSE for license details.

import os
import sys
import csv
import argparse
import logging
from operator import add
from functools import reduce
import numpy as np
import matplotlib
matplotlib.use('Agg') # No DISPLAY
import matplotlib.pyplot as plt
plt.rcParams.update({'font.size': 16})
plt.rcParams.update({'agg.path.chunksize': 10000})

def parse_args(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument("-p", "--power", dest="power", type=str,
                        help='power trace from FPGA', nargs='+')
    parser.add_argument("-d", "--dir", dest="dir", type=str,
                        help='output directory', default=os.path.curdir)
    parser.add_argument("--skip", dest="skip", type=int,
                        help="# of cycles to skip")
    args, _ = parser.parse_known_args(argv)

    if not os.path.isdir(args.dir):
        os.makedirs(args.dir)
    return args


def load_power_trace(filename, has_window=True):
    logging.info("Power trace file: %s", filename)
    window = None
    with open(filename, "r") as _f:
        reader = csv.reader(_f)
        for i, line in enumerate(reader):
            if i == 0 and has_window:
                # Window
                assert line[0] == 'window'
                window = int(line[1])
            elif i == 0 or i == 1 and has_window:
                # module names
                modules = line
                ps = [list() for _ in range(len(modules))]
            else:
                for j, p in enumerate(line):
                    ps[j].append(float(p))

    return window, modules, np.array(ps)


def plot_power(filename, y, cycles, window, title=""):
    """
    Plot time-based power
    """
    dirname = os.path.dirname(filename)
    if not os.path.isdir(dirname):
        os.makedirs(dirname)
    logging.info("Power Plot: %s", filename)
    intervals = np.arange(0, cycles, window)
    unit = ""
    if int(cycles / 1e9) > 5:
        intervals = intervals / 1e9
        unit = '(B)'
    elif int(cycles / 1e6) > 5:
        intervals = intervals / 1e6
        unit = '(M)'
    elif int(cycles / 1e3) > 5:
        intervals = intervals / 1e3
        unit = '(K)'

    xmax = np.max(intervals)
    ymax = 1.05 * np.max(y)
    ymin = 0.95 * np.min(y)

    plt.figure(figsize=(24, 8))

    plt.subplot(212)
    # plt.title("[Predict] " + title)
    plt.xlim((0.0, xmax))
    plt.ylim((ymin, ymax))
    plt.plot(intervals, y[:len(intervals)], 'g-')
    plt.ylabel("Predicted Power (mW)")
    plt.xlabel("Cycles %s" % unit)

    plt.savefig(filename, format="png", bbox_inches='tight')
    plt.close('all')


def plot_power_bars(filename, modules, ys, title=None):
    """
    Plot Power Breakdown
    """
    logging.info("Power Break-down: %s", filename)
    plt.figure(figsize=(8, 6))
    if title is not None:
        plt.title(title)
    y1_means = [np.mean(y) for y in ys]
    y1_stacks = [sum(y1_means[:i]) for i in range(len(y1_means))]
    bars = list()
    for i, (module, y1_mean, y1_stack) in \
        enumerate(zip(modules, y1_means, y1_stacks)):
        color = plot_power_bars.colors[i % len(plot_power_bars.colors)]
        bars.append(plt.bar(np.arange(1), [y1_mean], 0.5,
                            bottom=[y1_stack],
                            align='center',
                            color=color,
                            edgecolor='k',
                            linewidth=1.0,
                            label=module)[0])
    plt.xlim((-0.5, 1.5))
    plt.xticks(np.arange(1), ["Predict"])
    plt.ylabel("Power(mW)", fontsize='large')
    plt.legend(reversed(bars), reversed(modules), fontsize='x-small')
    plt.savefig(filename, format="png", bbox_inches='tight')
    plt.close('all')
plot_power_bars.colors = [
    'darkslategray',
    'mediumseagreen',
    'orangered',
    'c',
    'm',
    'y',
    'k',
    "peachpuff",
    "darkcyan",
    "peru",
    "orchid",
    "salmon",
    "lime"]


def store_power_bars(filename, modules, ys):
    logging.info("Power Bars in CSV: %s", filename)
    def _mean(y):
        return "%.2f" % y.mean()
    with open(filename, "w") as _f:
        writer = csv.writer(_f)
        writer.writerow(["Module", "Predicted Power"])
        writer.writerow([modules[0], _mean(ys[0])])
        for module, y in sorted(zip(modules[1:], ys[1:])):
            writer.writerow([module, _mean(y)])


def dump_power_bars(dirname, benchmark, labels, ys):
    csv_filename = os.path.join(dirname, "power-bars-%s.csv" % benchmark)
    png_filename = os.path.join(dirname, "power-bars-%s.png" % benchmark)
    idx = 1 if len(labels) > 1 else 0
    plot_power_bars(png_filename, labels[idx:], ys[idx:])
    store_power_bars(csv_filename, labels, ys)


def plot_trace(benchmark, args, trace):
    window, _modules, ps = load_power_trace(trace)
    p = reduce(add, ps[1:]) if len(ps) > 1 else ps[0]

    # Power Plot
    total_cycles = len(p) * window

    # Power Plot
    if args.skip:
        start_idx = (args.skip // window)
        total_cycles -= start_idx * window
        p = p[start_idx:]
        ps = [p[start_idx:] for p in ps]
    png_filename = os.path.join(args.dir, "%s-trace.png" % benchmark)
    plot_power(png_filename, p, total_cycles, window, benchmark)

    for i, (module, p) in enumerate(zip(_modules, ps)):
        png_filename = os.path.join(args.dir, module, benchmark + ".png")
        plot_power(png_filename, p, total_cycles, window, module)

    dump_power_bars(args.dir, benchmark, _modules, ps)


def main(argv):
    args = parse_args(argv)

    logging.basicConfig(
        format="%(message)s",
        level=logging.INFO
    )

    benchmarks = [
        os.path.splitext(os.path.basename(t))[0]
        for t in args.power
    ]

    for i, (benchmark, trace) in enumerate(zip(benchmarks, args.power)):
        plot_trace(benchmark, args, trace)

if __name__ == "__main__":
    main(sys.argv[1:])
