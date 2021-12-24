package pythia

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.{Config, Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.subsystem._

case class TilePrefetchingMasterPortParams(hartId: Int, base: TilePortParamsLike) extends TilePortParamsLike {
  val where = base.where
  def injectNode(context: Attachable)(implicit p: Parameters): TLNode = {
    TLPrefetcher() := base.injectNode(context)(p)
  }
}

class WithSingleNLPrefetcher extends Config((site, here, up) => {
  case TLPrefetcherKey => up(TLPrefetcherKey).copy(prefetcher=SingleNextLinePrefetcherParams())
})

