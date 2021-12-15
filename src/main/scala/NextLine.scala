package pythia

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import freechips.rocketchip.tilelink._

case class SingleNextLinePrefetcherParams(
  ahead: Int = 4
) extends CanInstantiatePrefetcher {
  def instantiate()(implicit p: Parameters) = Module(new SingleNextLinePrefetcher(this)(p))
}

class SingleNextLinePrefetcher(params: SingleNextLinePrefetcherParams)(implicit p: Parameters) extends AbstractPrefetcher()(p) {
  val active = RegInit(false.B)
  val write = Reg(Bool())
  val block_upper = Reg(UInt(22.W))
  val block_lower = Reg(UInt(10.W))
  val prefetch = Reg(UInt(10.W))
  val delta = prefetch - block_lower

  io.hit := (active && io.snoop.valid &&
    (block_upper === io.snoop.bits.block >> 10) &&
    (io.snoop.bits.block(9,0) >= block_lower) &&
    (io.snoop.bits.block(9,0) <= prefetch)
  )

  val snoop_next_block = io.snoop.bits.block(9,0) + 1.U

  when (!active) {
    active := io.snoop.valid && (~io.snoop.bits.block(9,0) =/= 0.U)
    block_upper := io.snoop.bits.block >> 10
    block_lower := io.snoop.bits.block
    prefetch := snoop_next_block
    write := io.snoop.bits.write
  }

  io.request.valid := active && prefetch =/= block_lower
  io.request.bits.write := write
  io.request.bits.address := Cat(block_upper, prefetch) << log2Up(io.request.bits.blockBytes)

  when (io.request.fire()) {
    active := prefetch =/= ~(0.U(10.W)) && delta =/= params.ahead.U
    prefetch := prefetch + 1.U
  }

  when (io.hit) {
    active := true.B
    write := io.snoop.bits.write
    block_lower := io.snoop.bits.block
    prefetch := snoop_next_block
  }

}
