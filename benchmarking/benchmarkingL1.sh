#!/bin/bash
# Run L1 prefetcher benchmark tests
# TODO: Add parameterization for other cores

cd ../../..
source env.sh
cd sims/vcs
make CONFIG=Prefetch2SaturnConfig
make run-binary CONFIG=Prefetch2SaturnConfig BINARY=$RISCV/riscv64-unknown-elf/share/riscv-tests/benchmarks/vvadd.riscv
cp output/chipyard.TestHarness.Prefetch2SaturnConfig/vvadd.out ../../generators/bar-prefetchers/benchmarking/prefetchL1-vvadd.out
make CONFIG=PassthroughPrefetchSaturnConfig
make run-binary CONFIG=PassthroughPrefetchSaturnConfig BINARY=$RISCV/riscv64-unknown-elf/share/riscv-tests/benchmarks/vvadd.riscv
cp output/chipyard.TestHarness.PassthroughPrefetchSaturnConfig/vvadd.out ../../generators/bar-prefetchers/benchmarking/no-prefetchL1-vvadd.out
cd ../../generators/bar-prefetchers/benchmarking
python L1-benchmarking.py "prefetchL1-vvadd.out" "no-prefetchL1-vvadd.out"