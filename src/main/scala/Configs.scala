package prefetchers

import freechips.rocketchip.config.{Config, Field, Parameters}
import freechips.rocketchip.rocket.{BuildHellaCache}

class WithTLDCachePrefetcher(p: CanInstantiatePrefetcher = MultiNextLinePrefetcherParams()) extends Config((site, here, up) => {
  case TLPrefetcherKey => up(TLPrefetcherKey).copy(
    prefetcher = (s: String) => if (s.contains("DCache") && !s.contains("MMIO")) Some(p) else up(TLPrefetcherKey).prefetcher(s)
  )
})

class WithTLICachePrefetcher(p: CanInstantiatePrefetcher = SingleNextLinePrefetcherParams()) extends Config((site, here, up) => {
  case TLPrefetcherKey => up(TLPrefetcherKey).copy(
    prefetcher = (s: String) => if (s.contains("ICache")) Some(p) else up(TLPrefetcherKey).prefetcher(s)
  )
})

class WithHellaCachePrefetcher(hartIds: Seq[Int], p: CanInstantiatePrefetcher = MultiNextLinePrefetcherParams()) extends Config((site, here, up) => {
  case BuildHellaCache => HellaCachePrefetchWrapperFactory.apply(hartIds, p, up(BuildHellaCache))
})

class WithPassthroughPrefetcher(p: CanInstantiatePrefetcher = PassthroughPrefetcherParams()) extends Config((site, here, up) => {
  case TLPrefetcherKey => up(TLPrefetcherKey).copy(
    prefetcher = (s: String) => if (s.contains("DCache") && !s.contains("MMIO")) Some(p) else up(TLPrefetcherKey).prefetcher(s)
  )
})
