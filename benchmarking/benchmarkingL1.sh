#!/bin/bash


# ---------------------------------------------------------------------------------------
# Run L1 prefetcher benchmark test
# Usage: ./benchmarkingL1.sh [prefetcher config] [non-prefetcher config] [path to binary]
# ---------------------------------------------------------------------------------------


# Borrowed from build-toolchains.sh
# On macOS, use GNU readlink from 'coreutils' package in Homebrew/MacPorts
if [ "$(uname -s)" = "Darwin" ] ; then
    READLINK=greadlink
else
    READLINK=readlink
fi

# If BASH_SOURCE is undefined, we may be running under zsh, in that case
# provide a zsh-compatible alternative
DIR="$(dirname "$($READLINK -f "${BASH_SOURCE[0]:-${(%):-%x}}")")"
CHIPYARD_DIR="$(dirname $(dirname $(dirname "$DIR")))"

OUT_FILE="$(basename "$3")"
OUT_FILE2=${OUT_FILE%.riscv}

cd $CHIPYARD_DIR/sims/vcs
make run-binary CONFIG=$1 BINARY=$3 EXTRA_SIM_FLAGS=+prefetcher_print_stats=1
make run-binary CONFIG=$2 BINARY=$3 EXTRA_SIM_FLAGS=+prefetcher_print_stats=1
cd $CHIPYARD_DIR/generators/bar-prefetchers/benchmarking
python3 L1-benchmarking.py "$CHIPYARD_DIR/sims/vcs/output/chipyard.TestHarness.$1/${OUT_FILE2}.out" "$CHIPYARD_DIR/sims/vcs/output/chipyard.TestHarness.$2/${OUT_FILE2}.out"