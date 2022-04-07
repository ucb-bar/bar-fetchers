#!/bin/bash
# Run L1 prefetcher benchmark test

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

cd $CHIPYARD_DIR/sims/vcs
make CONFIG=$1
make run-binary CONFIG=$1 BINARY=$RISCV/riscv64-unknown-elf/share/riscv-tests/benchmarks/vvadd.riscv
cp output/chipyard.TestHarness.$1/vvadd.out ../../generators/bar-prefetchers/benchmarking/prefetchL1-vvadd.out
make CONFIG=$2
make run-binary CONFIG=$2 BINARY=$RISCV/riscv64-unknown-elf/share/riscv-tests/benchmarks/vvadd.riscv
cp output/chipyard.TestHarness.$2/vvadd.out ../../generators/bar-prefetchers/benchmarking/no-prefetchL1-vvadd.out
cd $CHIPYARD_DIR/generators/bar-prefetchers/benchmarking
python L1-benchmarking.py "prefetchL1-vvadd.out" "no-prefetchL1-vvadd.out"