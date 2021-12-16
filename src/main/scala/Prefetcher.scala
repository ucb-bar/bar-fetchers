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

class TLPrefetcher(name: String)(implicit p: Parameters) extends LazyModule {
  val params = p(TLPrefetcherKey)
  val node = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1(
    sourceId = IdRange(0, params.prefetchIds),
    name = s"$name Prefetcher")))))

  lazy val module = new LazyModuleImp(this) {
    val io = IO(new Bundle {
      val snoop = Input(Valid(new PrefetchBundle))
    })
    val (tl_out, edge) = node.out(0)

    val prefetchers = params.prefetchers.map(_.instantiate())
    val prefetch_arb = Module(new Arbiter(new PrefetchBundle, prefetchers.size))

    prefetch_arb.io.in(0).valid := false.B
    prefetch_arb.io.in(0).bits := DontCare
    (prefetchers zip prefetch_arb.io.in) map { case (m, in) =>
      m.io.snoop := io.snoop
      in <> m.io.request
    }

    val tracker = RegInit(0.U(params.prefetchIds.W))
    val next_tracker = PriorityEncoder(~tracker)
    val tracker_free = !tracker(next_tracker)

    tracker := tracker ^ (tl_out.d.valid << tl_out.d.bits.source) ^ (tl_out.a.fire() << next_tracker)

    prefetch_arb.io.out.ready := tl_out.a.ready && tracker_free
    val blockSize = p(CacheBlockBytes)
    val (legal, hint) = edge.Hint(
      next_tracker,
      prefetch_arb.io.out.bits.block_address,
      log2Up(blockSize).U,
      Mux(prefetch_arb.io.out.bits.write, TLHints.PREFETCH_WRITE, TLHints.PREFETCH_READ)
    )
    tl_out.a.valid := prefetch_arb.io.out.valid && tracker_free && legal
    tl_out.a.bits := hint

    tl_out.d.ready := true.B
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
  def apply(name: String)(implicit p: Parameters) = {
    if (p(TLPrefetcherKey).prefetchers.size == 0) {
      TLTempNode()
    } else {
      val xbar = LazyModule(new TLSnoopingXbar(TLArbiter.highestIndexFirst))
      val prefetcher = LazyModule(new TLPrefetcher(name))
      xbar.node := prefetcher.node
      InModuleBody {
        prefetcher.module.io.snoop := xbar.snoop
      }
      xbar.node
    }
  }
}
