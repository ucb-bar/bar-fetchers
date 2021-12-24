package pythia

import freechips.rocketchip.config.{Config, Field, Parameters}

class WithSingleNLPrefetcher extends Config((site, here, up) => {
  case TLPrefetcherKey => up(TLPrefetcherKey).copy(prefetcher=SingleNextLinePrefetcherParams())
})

class WithMultiNLPrefetcher extends Config((site, here, up) => {
  case TLPrefetcherKey => up(TLPrefetcherKey).copy(prefetcher=MultiNextLinePrefetcherParams())
})
