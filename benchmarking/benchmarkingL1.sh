#!/bin/bash
# Run L1 prefetcher benchmark tests
# TODO: Add parameterization for other cores
# Prefetch2SaturnConfig, PassthroughPrefetchSaturnConfig

cd ../../..
source env.sh
cd sims/vcs
make CONFIG=$1
make run-binary CONFIG=$1 BINARY=$RISCV/riscv64-unknown-elf/share/riscv-tests/benchmarks/vvadd.riscv
cp output/chipyard.TestHarness.$1/vvadd.out ../../generators/bar-prefetchers/benchmarking/prefetchL1-vvadd.out
make CONFIG=$2
make run-binary CONFIG=$2 BINARY=$RISCV/riscv64-unknown-elf/share/riscv-tests/benchmarks/vvadd.riscv
cp output/chipyard.TestHarness.$2/vvadd.out ../../generators/bar-prefetchers/benchmarking/no-prefetchL1-vvadd.out
cd ../../generators/bar-prefetchers/benchmarking
python L1-benchmarking.py "prefetchL1-vvadd.out" "no-prefetchL1-vvadd.out"