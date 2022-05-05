package prefetchers

import chisel3._
import chisel3.util._
import chisel3.experimental.{IO}
import freechips.rocketchip.config.{Config, Field, Parameters}
import freechips.rocketchip.rocket._
import freechips.rocketchip.rocket.constants.{MemoryOpConstants}
import freechips.rocketchip.tile.{BaseTile}
import freechips.rocketchip.subsystem.{CacheBlockBytes}
import freechips.rocketchip.diplomacy._

object HellaCachePrefetchWrapperFactory {
  def apply(hartIds: Seq[Int], prefetcher: CanInstantiatePrefetcher, printPrefetchingStats: Boolean, base: BaseTile => Parameters => HellaCache) = (tile: BaseTile) => (p: Parameters) => {
    if (hartIds.contains(tile.staticIdForMetadataUseOnly)) {
      new HellaCachePrefetchWrapper(tile.staticIdForMetadataUseOnly, prefetcher, printPrefetchingStats, base(tile))(p)
    } else {
      base(tile)(p)
    }
  }
}

class HellaCachePrefetchWrapper(staticIdForMetadataUseOnly: Int, prefetcher: CanInstantiatePrefetcher, printPrefetchingStats: Boolean, inner: Parameters => HellaCache)(implicit p: Parameters) extends HellaCache(staticIdForMetadataUseOnly)(p) {
  val cache = LazyModule(inner(p))
  override val node = cache.node
  override val hartIdSinkNodeOpt = cache.hartIdSinkNodeOpt
  override val mmioAddressPrefixSinkNodeOpt = cache.mmioAddressPrefixSinkNodeOpt
  override lazy val module = new HellaCachePrefetchWrapperModule(prefetcher, printPrefetchingStats, this)
  def getOMSRAMs() = cache.getOMSRAMs()
}

class HellaCachePrefetchWrapperModule(pP: CanInstantiatePrefetcher, printPrefetchingStats: Boolean, outer: HellaCachePrefetchWrapper) extends HellaCacheModule(outer) with MemoryOpConstants{
  outer.cache.module.io <> io
  val cache = outer.cache.module

  val cycle_counter = RegInit(0.U(64.W))
  cycle_counter := cycle_counter + 1.U

  // Intercept and no-op prefetch requests generated by the core
  val core_prefetch = io.cpu.req.valid && isPrefetch(io.cpu.req.bits.cmd)
  when (io.cpu.req.valid && isPrefetch(io.cpu.req.bits.cmd)) {
    cache.io.cpu.req.valid := false.B
  }
  when (ShiftRegister(core_prefetch, 2)) {
    io.cpu.resp.valid := true.B
    io.cpu.s2_nack := false.B
    val req = ShiftRegister(io.cpu.req.bits, 2)
    val resp = io.cpu.resp.bits
    resp.addr := req.addr
    resp.tag := req.tag
    resp.cmd := req.cmd
    resp.size := req.size
    resp.signed := req.signed
    resp.dprv := req.dprv
    resp.data := req.data
    resp.mask := req.mask
    resp.replay := false.B
    resp.has_data := false.B
    resp.data_word_bypass := false.B
    resp.data_raw := false.B
    resp.store_data := false.B
  }
  when (cache.io.cpu.resp.valid && isPrefetch(cache.io.cpu.resp.bits.cmd)) {
    io.cpu.resp.valid := false.B
  }

  val prefetcher = pP.instantiate()

  prefetcher.io.snoop.valid := ShiftRegister(io.cpu.req.fire() && !core_prefetch, 2) && !io.cpu.s2_nack && !RegNext(io.cpu.s1_kill)
  prefetcher.io.snoop.bits.address := ShiftRegister(io.cpu.req.bits.addr, 2)
  prefetcher.io.snoop.bits.write := ShiftRegister(isWrite(io.cpu.req.bits.cmd), 2)

  val req = Queue(prefetcher.io.request, 1)
  val in_flight = RegInit(false.B)
  req.ready := false.B

  when (!io.cpu.req.valid) {
    cache.io.cpu.req.valid := req.valid && !in_flight
    cache.io.cpu.req.bits.addr := req.bits.block_address
    cache.io.cpu.req.bits.tag := 0.U
    cache.io.cpu.req.bits.cmd := Mux(req.bits.write, M_PFW, M_PFR)
    cache.io.cpu.req.bits.size := 0.U
    cache.io.cpu.req.bits.signed := false.B
    cache.io.cpu.req.bits.dprv := DontCare
    cache.io.cpu.req.bits.data := DontCare
    cache.io.cpu.req.bits.mask := DontCare
    cache.io.cpu.req.bits.phys := false.B
    cache.io.cpu.req.bits.no_alloc := false.B
    cache.io.cpu.req.bits.no_xcpt := false.B
    when (cache.io.cpu.req.fire()) { in_flight := true.B }
    if (printPrefetchingStats) {
      when (cache.io.cpu.req.fire()) {
        //print prefetch
        val last_prefetch_addr = req.bits.block_address
        printf(p"Cycle: ${Decimal(cycle_counter)}\tPrefetchAddr: ${Hexadecimal(req.bits.block_address)}\n")
      }
    }
  }
  
  //print snoop
  if (printPrefetchingStats) {
    when (prefetcher.io.snoop.valid) {
      val last_snoop_addr = prefetcher.io.snoop.bits.address
      printf(p"Cycle: ${Decimal(cycle_counter)}\tSnoopAddr: ${Hexadecimal(prefetcher.io.snoop.bits.address)}\tSnoopBlock: ${Hexadecimal(prefetcher.io.snoop.bits.block_address)}\n")
    }

    //print response
    when (cache.io.cpu.resp.valid && !isPrefetch(cache.io.cpu.resp.bits.cmd)) {
      printf(p"Cycle: ${Decimal(cycle_counter)}\tSnoopRespAddr: ${Hexadecimal(cache.io.cpu.resp.bits.addr)}\n")
    }

    //print prefetch response
    when (cache.io.cpu.resp.valid && isPrefetch(cache.io.cpu.resp.bits.cmd)) {
      printf(p"Cycle: ${Decimal(cycle_counter)}\tPrefetchRespAddr: ${Hexadecimal(cache.io.cpu.resp.bits.addr)}\n")
    }
  }

  val prefetch_fire = cache.io.cpu.req.fire() && isPrefetch(cache.io.cpu.req.bits.cmd)
  when (ShiftRegister(prefetch_fire, 1)) {
    cache.io.cpu.s1_kill := false.B
  }
  when (ShiftRegister(prefetch_fire, 2)) {
    // HellaCache ignores DTLB prefetchable signal, so we recompute it here,
    // and kill the request in s2 if not prefetchable
    val paddr = cache.io.cpu.s2_paddr
    val legal = cache.edge.manager.findSafe(paddr).reduce(_||_)
    val prefetchable = cache.edge.manager.fastProperty(paddr, _.supportsAcquireT,
      (b: TransferSizes) => (!b.none).B)
    cache.io.cpu.s2_kill := !legal || !prefetchable
    req.ready := !cache.io.cpu.s2_nack
    in_flight := false.B
  }
  when (ShiftRegister(!io.cpu.req.valid, 2)) {
    io.cpu.s2_nack := false.B
  }
}
