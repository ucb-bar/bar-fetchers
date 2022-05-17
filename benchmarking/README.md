# Prefetcher Benchmarking

This benchmarking script computes prefetcher coverage, accuracy and timeliness.

__Coverage__ is the percentage of misses prevented by prefetching. This is calculated here by dividing misses prevented by misses with the prefetch config plus misses prevented. Misses prevented is calculated by looking at each prefetch, and checking if that address was a miss in the non-prefetch config and a hit in the prefetch config.

__Accuracy__ is the percentage of prefetches that prevent misses. This is calculated here by dividing misses prevented by useless prefetches plus misses prevented. For regular accuracy, a useless prefetch is either a prefetch that doesn't get acknowledged or a prefetch that doesn't prevent a miss. For acknowledged accuracy, a useless prefetch is a unique prefetched address that doesn't turn the address it's prefetched for from a miss into a hit.

__Timeliness__ is a measure of how far a prefetch occurs before the memory address is accessed. Here, timeliness is the average number of cycles between when a prefetch was last responded to and when that address is accessed.

To run the L1 prefetching benchmark, run
```
./benchmarkingL1.sh [prefetch config] [non-prefetch config] [path to binary]
```