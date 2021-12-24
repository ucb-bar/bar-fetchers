package pythia

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import freechips.rocketchip.tilelink._

case class SingleNextLinePrefetcherParams(
  ahead: Int = 4,
  waitForHit: Boolean = false
) extends CanInstantiatePrefetcher {
  def instantiate()(implicit p: Parameters) = Module(new SingleNextLinePrefetcher(this)(p))
}

class SingleNextLinePrefetcher(params: SingleNextLinePrefetcherParams)(implicit p: Parameters) extends AbstractPrefetcher()(p) {
  val s_idle :: s_wait :: s_active :: Nil = Enum(3)
  val state = RegInit(s_idle)
  val write = Reg(Bool())
  val block_upper = Reg(UInt())
  val block_lower = Reg(UInt(6.W))
  val prefetch = Reg(UInt(6.W))
  val delta = prefetch - block_lower

  io.hit := (state =/= s_idle &&
    (block_upper === io.snoop.bits.block >> 6) &&
    (io.snoop.bits.block(5,0) >= block_lower) &&
    (io.snoop.bits.block(5,0) <= prefetch)
  )

  val snoop_next_block = io.snoop.bits.block(5,0) + 1.U

  when ((state === s_idle || (state === s_wait && !io.hit)) && io.snoop.valid) {
    when (~io.snoop.bits.block(5,0) =/= 0.U) {
      state := (if (params.waitForHit) s_wait else s_active)
    }
    block_upper := io.snoop.bits.block >> 6
    block_lower := io.snoop.bits.block
    prefetch := snoop_next_block
    write := io.snoop.bits.write
  }

  io.request.valid := state === s_active && prefetch =/= block_lower
  io.request.bits.write := write
  io.request.bits.address := Cat(block_upper, prefetch) << log2Up(io.request.bits.blockBytes)

  when (io.request.fire()) {
    state := Mux(prefetch =/= ~(0.U(6.W)) && delta =/= params.ahead.U, s_active, s_idle)
    prefetch := prefetch + 1.U
  }

  when (io.hit && io.snoop.valid) {
    state := s_active
    write := io.snoop.bits.write
    block_lower := io.snoop.bits.block
    prefetch := snoop_next_block
  }
}


case class MultiNextLinePrefetcherParams(
  singles: Seq[SingleNextLinePrefetcherParams] = Seq.fill(4) { SingleNextLinePrefetcherParams() }
) extends CanInstantiatePrefetcher {
  def instantiate()(implicit p: Parameters) = Module(new MultiNextLinePrefetcher(this)(p))
}

class MultiNextLinePrefetcher(params: MultiNextLinePrefetcherParams)(implicit p: Parameters) extends AbstractPrefetcher()(p) {

  val singles = params.singles.map(_.copy(waitForHit=true).instantiate())
  val any_hit = singles.map(_.io.hit).reduce(_||_)
  singles.foreach(_.io.snoop.valid := false.B)
  singles.foreach(_.io.snoop.bits := io.snoop.bits)
  val repl = RegInit(1.U(singles.size.W))
  when (io.snoop.valid) {
    repl := repl << 1 | repl(singles.size-1)
    for (s <- 0 until singles.size) {
      when (singles(s).io.hit || (!any_hit && repl(s))) { singles(s).io.snoop.valid := true.B }
    }
  }
  val arb = Module(new RRArbiter(new Prefetch, singles.size))
  arb.io.in <> singles.map(_.io.request)
  io.request <> arb.io.out
}
