package prefetchers

import org.chipsalliance.cde.config.{Config, Field, Parameters}
import freechips.rocketchip.rocket.{BuildHellaCache}

class WithTLDCachePrefetcher(p: Seq[CanInstantiatePrefetcher] = Seq(MultiNextLinePrefetcherParams())) extends Config((site, here, up) => {
  case TLPrefetcherKey => up(TLPrefetcherKey).copy(
    prefetcher = (s: String) => if (s.contains("DCache") && !s.contains("MMIO")) Some(p) else up(TLPrefetcherKey).prefetcher(s)
  )
})

class WithTLICachePrefetcher(p: Seq[CanInstantiatePrefetcher] = Seq(SingleNextLinePrefetcherParams())) extends Config((site, here, up) => {
  case TLPrefetcherKey => up(TLPrefetcherKey).copy(
    prefetcher = (s: String) => if (s.contains("ICache")) Some(p) else up(TLPrefetcherKey).prefetcher(s)
  )
})

class WithHellaCachePrefetcher(hartIds: Seq[Int], p: CanInstantiatePrefetcher = MultiNextLinePrefetcherParams(handleVA=true)) extends Config((site, here, up) => {
  case BuildHellaCache => HellaCachePrefetchWrapperFactory.apply(hartIds, p, up(BuildHellaCache))
})
