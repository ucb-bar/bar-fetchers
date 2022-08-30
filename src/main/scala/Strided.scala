package prefetchers

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.subsystem.{CacheBlockBytes}

case class SingleStridedPrefetcherParams(
    history: Int = 4, //Number of times a delta must be seen before prefetching
    ahead: Int = 1 // TODO: add assertion to force power of 2
) extends CanInstantiatePrefetcher {
  def desc() = "Single Strided Prefetcher"
  def instantiate()(implicit p: Parameters) = Module(new StridedPrefetcher(this)(p))
}

class StridedPrefetcher(params: SingleStridedPrefetcherParams)(implicit p: Parameters) extends AbstractPrefetcher()(p) {
  val historyWidth = log2Up(params.history * 2)
  val block_bits = log2Up(io.request.bits.blockBytes)
  val ahead_bits = log2Up(params.ahead)

  val s_idle :: s_wait :: s_active :: s_done :: Nil = Enum(4)
  val state = RegInit(s_idle)
  val prefetch = Reg(UInt())
  val delta = Reg(UInt())
  val last_delta = Reg(UInt())
  val history_cnt = RegInit(0.U(historyWidth.W))
  val last_snoop = Reg(UInt())
  val last_write = Reg(Bool())
  val delta_pos = Reg(Bool())
  val last_delta_pos = Reg(Bool())
  //val sent_pref_cnt = Reg(UInt(ahead_bits.W))
  //val prefetch_amt = Reg(UInt())

  last_snoop := Mux(io.snoop.valid, io.snoop.bits.address, last_snoop)
  last_write := Mux(io.snoop.valid, io.snoop.bits.write, last_write)
  last_delta := Mux(io.snoop.valid, io.snoop.bits.address - last_snoop, last_delta)
  last_delta_pos := Mux(io.snoop.valid, io.snoop.bits.address > last_snoop, last_delta_pos)

  when (io.snoop.valid) {
    //BUG: should check against prefetch delta
    when (last_delta === (io.snoop.bits.address - last_snoop)) {
      //Saturating counter
      history_cnt := Mux(history_cnt === ((params.history * 2) - 1).U, history_cnt, history_cnt + 1.U)
    } .otherwise {
      history_cnt := Mux(history_cnt === 0.U, history_cnt, history_cnt - 1.U)
    }
  }

  when (history_cnt >= params.history.U && state === s_idle) {
    //Begin prefetching
    state := s_active
    delta := last_delta
    delta_pos := last_delta_pos
    prefetch := last_snoop + last_delta
    when (delta < io.request.bits.blockBytes.U) {
      io.snoop.bits.address + (1.U << block_bits.U)
    }
  }

  when (history_cnt < params.history.U) {
    state := s_idle
  }

  val pref_far_enough = RegInit(false.B)

  //buggy, L1 thing tho
  pref_far_enough := prefetch - last_snoop >= (1.U << block_bits.U)

  io.request.valid := state === s_active && pref_far_enough
  io.request.bits.address := prefetch
  io.request.bits.write := last_write

  when (!pref_far_enough) {
    prefetch := prefetch + delta
  }

  when (state === s_wait) {
    when ((delta_pos && (prefetch - last_snoop) < (delta << ahead_bits)) || (!delta_pos && (last_snoop - prefetch) < (delta << ahead_bits))) {
      state := s_active
    }
  }

  when (io.request.fire()) {
    prefetch := prefetch + delta
    when ((prefetch - last_snoop) < (delta << ahead_bits)) {
      //Only continue prefetching if delta is still same
      state := Mux(history_cnt >= params.history.U, s_active, s_idle)
    } .otherwise {
      state := s_wait
    }
  }

}