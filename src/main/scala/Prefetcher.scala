package pythia

import chisel3._
import chisel3.util._
import chisel3.experimental.{IO}
import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.subsystem.{CacheBlockBytes}

trait CanInstantiatePrefetcher {
  def instantiate()(implicit p: Parameters): AbstractPrefetcher
}

case class TLPrefetcherParams(
  prefetchIds: Int = 4,
  prefetchers: Seq[CanInstantiatePrefetcher] = Seq(SingleNextLinePrefetcherParams())
)

case object TLPrefetcherKey extends Field[TLPrefetcherParams](TLPrefetcherParams())

class PrefetchBundle(implicit val p: Parameters) extends Bundle {
  val blockBytes = p(CacheBlockBytes)

  val write = Bool()
  val address = UInt()
  def block = address >> log2Up(blockBytes)
  def block_address = block << log2Up(blockBytes)
}

class PrefetcherIO(implicit p: Parameters) extends Bundle {
  val snoop = Input(Valid(new PrefetchBundle))
  val request = Decoupled(new PrefetchBundle)
  val hit = Output(Bool())
}

abstract class AbstractPrefetcher(implicit p: Parameters) extends Module {
  val io = IO(new PrefetcherIO)

  io.request.valid := false.B
  io.request.bits := DontCare
  io.request.bits.address := 0.U(1.W)
  io.hit := false.B
}

class NullPrefetcher(implicit p: Parameters) extends AbstractPrefetcher()(p)

class TLPrefetcher(implicit p: Parameters) extends LazyModule {
  val params = p(TLPrefetcherKey)

  def mapInputIds(masters: Seq[TLMasterParameters]) = TLXbar.assignRanges(masters.map(_.sourceId.size + params.prefetchIds))

  val node = TLAdapterNode(
    clientFn = { cp =>
      println(cp.clients)
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

      println(inIdMap)
      println(outIdMap)


      val snoop = Wire(Valid(new PrefetchBundle))
      val snoop_client = Wire(UInt(log2Ceil(nClients).W))

      // Implement prefetchers per client. TODO: Support heterogenous prefetchers per client
      val prefetchers = Seq.fill(nClients) { params.prefetchers.map(_.instantiate()) }
      val client_arbs = prefetchers.zipWithIndex.map { case (f,i) =>
        f.foreach(_.io.snoop.valid := snoop.valid && snoop_client === i.U)
        f.foreach(_.io.snoop.bits := snoop.bits)

        val arb = Module(new Arbiter(new PrefetchBundle, f.size))
        arb.io.in <> f.map(_.io.request)
        arb
      }
      val out_arb = Module(new RRArbiter(new PrefetchBundle, nClients))
      out_arb.io.in <> client_arbs.map(_.io.out)

      val tracker = RegInit(0.U(params.prefetchIds.W))
      val next_tracker = PriorityEncoder(~tracker)
      val tracker_free = !tracker(next_tracker)

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

      val (legal, hint) = edgeOut.Hint(
        prefetchIdToOutId(next_tracker, out_arb.io.chosen),
        out_arb.io.out.bits.block_address,
        log2Up(p(CacheBlockBytes)).U,
        Mux(out_arb.io.out.bits.write, TLHints.PREFETCH_WRITE, TLHints.PREFETCH_READ)
      )
      out_arb.io.out.ready := false.B
      when (!in.a.valid) {
        out.a.valid := out_arb.io.out.valid && tracker_free && legal
        out.a.bits := hint
        out_arb.io.out.ready := out.a.ready
      }
      when (!legal) {
        out_arb.io.out.ready := true.B
      }
    }
  }
}

class TLSnoopingXbar(policy: TLArbiter.Policy = TLArbiter.roundRobin)(implicit p: Parameters) extends TLXbar(policy)(p)
{
  // TODO: Make this snoop C channel as well
  val snoop = InModuleBody {
    require(node.in.size == 2)
    val snoop = IO(Output(Valid(new PrefetchBundle)))
    val edge = node.in(1)._2
    val tl_a = node.in(1)._1.a

    snoop.valid := tl_a.fire()
    val acq = tl_a.bits.opcode.isOneOf(TLMessages.AcquireBlock, TLMessages.AcquirePerm)
    val toT = tl_a.bits.param.isOneOf(TLPermissions.NtoT, TLPermissions.BtoT)
    val put = edge.hasData(tl_a.bits)
    snoop.bits.write := put || (acq && toT)
    snoop.bits.address := tl_a.bits.address
    snoop
  }
}

object TLPrefetcher {
  def apply()(implicit p: Parameters) = {
    if (p(TLPrefetcherKey).prefetchers.size == 0) {
      TLTempNode()
    } else {
      val prefetcher = LazyModule(new TLPrefetcher)
      prefetcher.node
    }
  }
}
