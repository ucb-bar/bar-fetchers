#!/bin/bash
# Run prefetcher benchmark tests

cd ../../..
source env.sh
cd sims/vcs
make CONFIG=PrefetcherRocketConfig
make run-binary CONFIG=PrefetcherRocketConfig BINARY=$RISCV/riscv64-unknown-elf/share/riscv-tests/benchmarks/vvadd.riscv
cp output/chipyard.TestHarness.PrefetcherRocketConfig/vvadd.out ../../generators/bar-prefetchers/benchmarking/prefetch-vvadd.out
make CONFIG=PassthroughPrefetcherRocketConfig
make run-binary CONFIG=PassthroughPrefetcherRocketConfig BINARY=$RISCV/riscv64-unknown-elf/share/riscv-tests/benchmarks/vvadd.riscv
cp output/chipyard.TestHarness.PassthroughPrefetcherRocketConfig/vvadd.out ../../generators/bar-prefetchers/benchmarking/no-prefetch-vvadd.out
cd ../../generators/bar-prefetchers/benchmarking
python coverage.py "prefetch-vvadd.out" "no-prefetch-vvadd.out"