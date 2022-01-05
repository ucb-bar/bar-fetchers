package pythia

import freechips.rocketchip.config.{Config, Field, Parameters}
import pythia._

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
