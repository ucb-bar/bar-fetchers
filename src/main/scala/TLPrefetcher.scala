package prefetchers

import chisel3._
import chisel3.util._
import chisel3.experimental.{IO}
import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.subsystem._

case class TLPrefetcherParams(
  prefetchIds: Int = 4,
  prefetcher: String => Option[CanInstantiatePrefetcher] = _ => None
)

case object TLPrefetcherKey extends Field[TLPrefetcherParams](TLPrefetcherParams())

class TLPrefetcher(implicit p: Parameters) extends LazyModule {
  val params = p(TLPrefetcherKey)

  def mapInputIds(masters: Seq[TLMasterParameters]) = TLXbar.assignRanges(masters.map(_.sourceId.size + params.prefetchIds))

  val node = TLAdapterNode(
    clientFn = { cp =>
      cp.v1copy(clients = (mapInputIds(cp.clients) zip cp.clients).map { case (range, c) => c.v1copy(
        sourceId = range
      )})
    },
    managerFn = { mp => mp }
  )

  // Handle size = 1 gracefully (Chisel3 empty range is broken)
  def trim(id: UInt, size: Int): UInt = if (size <= 1) 0.U else id(log2Ceil(size)-1, 0)

  lazy val module = new LazyModuleImp(this) {
    (node.in zip node.out) foreach { case ((in, edgeIn), (out, edgeOut)) =>
      val nClients = edgeOut.master.clients.size
      val outIdMap = edgeOut.master.clients.map(_.sourceId)
      val inIdMap = edgeIn.master.clients.map(_.sourceId)

      val snoop = Wire(Valid(new Snoop))
      val snoop_client = Wire(UInt(log2Ceil(nClients).W))

      val enable_print_stats = PlusArg("prefetcher_print_stats", width=1, default=0)(0)

      // Implement prefetchers per client.
      val prefetchers = edgeOut.master.clients.zipWithIndex.map { case (c,i) =>
        val pParams = params.prefetcher(c.name).getOrElse(NullPrefetcherParams())
        println(s"Prefetcher for ${c.name}: ${pParams.desc}")
        val prefetcher = pParams.instantiate()
        prefetcher.io.snoop.valid := snoop.valid && snoop_client === i.U
        prefetcher.io.snoop.bits := snoop.bits
        prefetcher
      }

      val out_arb = Module(new RRArbiter(new Prefetch, nClients))
      out_arb.io.in <> prefetchers.map(_.io.request)

      val tracker = RegInit(0.U(params.prefetchIds.W))
      val next_tracker = PriorityEncoder(~tracker)
      val tracker_free = !tracker(next_tracker)

      val cycle_counter = RegInit(0.U(64.W))

      def inIdAdjuster(source: UInt) = Mux1H((inIdMap zip outIdMap).map { case (i,o) =>
        i.contains(source) -> (o.start.U | (source - i.start.U))
      })
      def outIdAdjuster(source: UInt) = Mux1H((inIdMap zip outIdMap).map { case (i,o) =>
        o.contains(source) -> (trim(source - o.start.U, i.size) + i.start.U)
      })
      def outIdToPrefetchId(source: UInt) = Mux1H((inIdMap zip outIdMap).map { case (i,o) =>
        o.contains(source) -> trim(source - (o.start + i.size).U, params.prefetchIds)
      })
      def prefetchIdToOutId(source: UInt, client: UInt) = Mux1H((inIdMap zip outIdMap).zipWithIndex.map { case ((i,o),id) =>
        (id.U === client) -> ((o.start + i.size).U +& source)
      })
      def inIdToClientId(source: UInt) = Mux1H(inIdMap.zipWithIndex.map { case (i,id) =>
        i.contains(source) -> id.U
      })

      out <> in
      out.a.bits.source := inIdAdjuster(in.a.bits.source)
      in.b.bits.source := outIdAdjuster(out.b.bits.source)
      out.c.bits.source := inIdAdjuster(in.c.bits.source)
      val d_is_prefetch = out.d.bits.opcode === TLMessages.HintAck
      in.d.valid := out.d.valid && !d_is_prefetch
      when (d_is_prefetch) { out.d.ready := true.B }
      in.d.bits.source := outIdAdjuster(out.d.bits.source)

      tracker := (tracker
        ^ ((out.d.valid && d_is_prefetch) << outIdToPrefetchId(out.d.bits.source))
        ^ ((out.a.fire() && !in.a.valid) << next_tracker)
      )
      snoop.valid := in.a.fire() && edgeIn.manager.supportsAcquireBFast(in.a.bits.address, log2Ceil(p(CacheBlockBytes)).U)
      snoop.bits.address := in.a.bits.address
      val acq = in.a.bits.opcode.isOneOf(TLMessages.AcquireBlock, TLMessages.AcquirePerm)
      val toT = in.a.bits.param.isOneOf(TLPermissions.NtoT, TLPermissions.BtoT)
      val put = edgeIn.hasData(in.a.bits)
      snoop.bits.write := put || (acq && toT)
      snoop_client := inIdToClientId(in.a.bits.source)

      //Add cycle counter
      cycle_counter := cycle_counter + 1.U

      //Memory request buffer
      val reqBufferSize = 16
      val reqBufferInsert = Reg(Bool())
      val reqBuffer = Reg(Vec(reqBufferSize, Valid(new RequestBufferEntry)))
      val reqBufferHead = Reg(UInt(log2Ceil(reqBufferSize).W))
      val lastHit = Reg(Valid(new RequestBufferEntry))

      reqBufferInsert := out.a.valid && out.a.bits.opcode =/= TLMessages.Hint

      //insert into buffer
      when(reqBufferInsert) {
        reqBuffer(reqBufferHead).valid := true.B
        reqBuffer(reqBufferHead).bits.addr := out.a.bits.address
        reqBuffer(reqBufferHead).bits.cycleSent := cycle_counter
        reqBuffer(reqBufferHead).bits.source := out.a.bits.source
        reqBufferHead := reqBufferHead + 1.U
      }

      for (i <- 0 until reqBufferSize) {
        when (out.d.valid) {
          when (reqBuffer(i).valid && (reqBuffer(i).bits.source === out.d.bits.source) && (out.d.bits.opcode =/= TLMessages.Hint)) {
            lastHit.valid := cycle_counter - reqBuffer(i).bits.cycleSent <= 5.U
            lastHit.bits := reqBuffer(i).bits
            //invalidate entry unless entry is being inserted to (no double writes)
            when (!(i.U === reqBufferHead && reqBufferInsert)) {
              reqBuffer(i).valid := false.B
            }
          }
        }
      }

      //prefetch buffer
      val prefetchBufferSize = 64 //SHOULD BE A POWER OF 2 BECAUSE LAZY
      val prefetchBuffer = Reg(Vec(prefetchBufferSize, Valid(new PrefetchBufferEntry())))
      val prefBufferHead = RegInit(0.U(log2Ceil(prefetchBufferSize).W))
      val usefulPrefVec = Reg(Vec(prefetchBufferSize, Bool()))
      //val usefulPref = Reg(Bool())
      val prefBufferInsert = Wire(Bool())
      val lastPrefTime = RegInit(0.U(64.W))
      val lastNMinus1Times = RegInit(0.U(64.W))

      prefBufferInsert := out.a.valid && out.a.bits.opcode === TLMessages.Hint

      //insert into buffer
      when(prefBufferInsert) {
        prefetchBuffer(prefBufferHead).valid := true.B
        prefetchBuffer(prefBufferHead).bits.addr := out.a.bits.address
        prefetchBuffer(prefBufferHead).bits.cycleSent := cycle_counter
        prefBufferHead := prefBufferHead + 1.U
      }

      //check if resp addr is in pref buffer
      for (i <- 0 until prefetchBufferSize) {
        when (lastHit.valid) {
          // no d-channel address......
          when (prefetchBuffer(i).valid && (prefetchBuffer(i).bits.addr === lastHit.bits.addr)) {
            usefulPrefVec(i) := true.B
            lastPrefTime := cycle_counter - prefetchBuffer(i).bits.cycleSent
            lastNMinus1Times := lastNMinus1Times + lastPrefTime
            //invalidate entry unless entry is being inserted to (no double writes)
            when (!(i.U === prefBufferHead && prefBufferInsert)) {
              prefetchBuffer(i).valid := false.B
            }
          } .otherwise {
            usefulPrefVec(i) := false.B
          }
        } .otherwise {
          usefulPrefVec(i) := false.B
        }
      }
      val usefulPref = Wire(Bool())
      usefulPref := usefulPrefVec.reduce(_ || _)

      midas.targetutils.PerfCounter.identity(lastPrefTime, "lastPrefTime", "Timeliness value for latest prefetch")
      midas.targetutils.PerfCounter.identity(lastNMinus1Times, "lastNMinus1Times", "Sum of timeliness values for latest N-1 prefetches")
      midas.targetutils.PerfCounter(usefulPref, "useful_pref", "Useful Prefetches")
      midas.targetutils.PerfCounter(out.a.valid && (out.a.bits.opcode === TLMessages.Hint), "pref", "Prefetches Sent")
      midas.targetutils.PerfCounter(out.a.valid && (out.a.bits.opcode =/= TLMessages.Hint), "core_req", "Core Memory Requests Sent")

      when (out.a.valid) {
        val snoopAddrPrint = out.a.bits.address
        val snoopTxId = out.a.bits.source //Hopefully this aligns correctly
        when (enable_print_stats) {
          when (out.a.bits.opcode === TLMessages.Hint) {
            printf(p"Cycle: ${Decimal(cycle_counter)}\tPrefAddr: 0x${Hexadecimal(out.a.bits.address)}\tPrefID: ${Decimal(snoopTxId)}\n")
          } .otherwise {
            printf(p"Cycle: ${Decimal(cycle_counter)}\tSnoopAddr: 0x${Hexadecimal(snoopAddrPrint)}\tSnoopID: ${Decimal(snoopTxId)}\n")
          }
        }
      }

      //Print d channel response + ID + cycles
      //ID: source
      //Response: valid
      // cycles tell hit or miss
      // Response coming back from L2
      when (enable_print_stats && out.d.valid) {
        val dResponseID = out.d.bits.source
        printf(p"Cycle: ${Decimal(cycle_counter)}\tRespID: ${Decimal(dResponseID)}\n")
      }

      val legal_address = edgeOut.manager.findSafe(out_arb.io.out.bits.block_address).reduce(_||_)
      val (legal, hint) = edgeOut.Hint(
        prefetchIdToOutId(next_tracker, out_arb.io.chosen),
        out_arb.io.out.bits.block_address,
        log2Up(p(CacheBlockBytes)).U,
        Mux(out_arb.io.out.bits.write, TLHints.PREFETCH_WRITE, TLHints.PREFETCH_READ)
      )
      out_arb.io.out.ready := false.B
      when (!in.a.valid) {
        out.a.valid := out_arb.io.out.valid && tracker_free && legal && legal_address
        out.a.bits := hint
        out_arb.io.out.ready := out.a.ready
        /*val prefetchAddrPrint = out.a.bits.address
        when (out.a.valid) {
          printf(p"Cycle: ${Decimal(cycle_counter)}\tPrefetch addr: 0x${Hexadecimal(prefetchAddrPrint)}" + "\n")
        }*/
      }
      when (!legal || !legal_address) {
        out_arb.io.out.ready := true.B
      }
    }
  }
}

object TLPrefetcher {
  def apply()(implicit p: Parameters) = {
    val prefetcher = LazyModule(new TLPrefetcher)
    prefetcher.node
  }
}

case class TilePrefetchingMasterPortParams(hartId: Int, base: TilePortParamsLike) extends TilePortParamsLike {
  val where = base.where
  def injectNode(context: Attachable)(implicit p: Parameters): TLNode = {
    TLPrefetcher() :*=* base.injectNode(context)(p)
  }
}

class PrefetchBufferEntry extends Bundle {
  val addr = UInt() // TODO: SET WIDTH
  val cycleSent = UInt(64.W)
}

class RequestBufferEntry extends Bundle {
  val addr = UInt()
  val cycleSent = UInt()
  val source = UInt()
}
