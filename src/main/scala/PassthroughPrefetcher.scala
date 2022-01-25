package prefetchers

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import freechips.rocketchip.tilelink._

case class PassthroughPrefetcherParams(
) extends CanInstantiatePrefetcher {
  def desc() = "Passthrough Prefetcher"
  def instantiate()(implicit p: Parameters) = Module(new PassthroughPrefetcher(this)(p))
}

class PassthroughPrefetcher(params: PassthroughPrefetcherParams)(implicit p: Parameters) extends AbstractPrefetcher()(p) {
    io.request.valid := false.B //don't send any prefetch requests
}
