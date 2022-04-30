package prefetchers

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.subsystem.{CacheBlockBytes}

case class SingleAMPMPrefetcherParams(
  //config given in paper: 256 entries, N=64, ways=8
  entries: Int = 32,
  N: Int = 8, //Number of cache lines per zone
  ways: Int = 4
) extends CanInstantiatePrefetcher {
  def desc() = "Single AMPM Prefetcher"
  def instantiate()(implicit p: Parameters) = Module(new AMPMPrefetcher(this)(p))
}

class AMPMPrefetcher(params: SingleAMPMPrefetcherParams)(implicit p: Parameters) extends AbstractPrefetcher()(p) {
    //reges
    val entries_per_way = params.entries >> params.ways
    val line_bits = log2Ceil(params.N)
    val way_bits = log2Ceil(params.ways)
    val max_k = if (params.N % 2 == 0) { params.N / 2 } else { (params.N - 1) / 2}
    println("Block bytes: %d\n".format(io.snoop.bits.blockBytes))
    println("entries per way: %d\n".format(entries_per_way))
    println("line bits: %d\n".format(line_bits))
    println("way bits: %d\n".format(way_bits))
    
    //memory access map table
    //shift out bank as well
    val snoop_tag = io.snoop.bits.block >> (line_bits + way_bits)
    //TODO: shift by line bits before indexing
    val snoop_bank = (io.snoop.bits.block >> line_bits)(way_bits - 1, 0)
    val snoop_offset = io.snoop.bits.block(line_bits - 1, 0)
    // stores memory access banks
    val memory_access_map_table = Reg(Vec(params.ways, Valid(new MemoryAccessMapBank(entries_per_way = entries_per_way, N = params.N))))
    val tag_candidate = Wire(Vec(params.ways, UInt(params.N.W)))
    val prefetch_input = Wire(UInt(params.N.W))
    val entry_existence_vec = Wire(Vec(params.ways * entries_per_way, Bool()))
    val entry_existence = Wire(Bool())
    val initial_state_vec = Wire(Vec(params.N, Bool())) 
    val head = RegInit(VecInit(Seq.fill(params.ways)(0.U((entries_per_way - 1).W))))

    for (i <- 0 until params.N) {
      initial_state_vec(i) := snoop_offset === i.U
    }
    //parallelize
    for (i <- 0 until params.ways) {
      tag_candidate(i) := 0.U
      val map_bank = memory_access_map_table(i)
      for (j <- 0 until entries_per_way) {
        val map = map_bank.bits.maps(j)
        when (io.snoop.valid && map.valid && (map.bits.tag === snoop_tag)) {
          //found entry -> send to prefetch generator
          tag_candidate(i) := map.bits.states.asUInt
          //Set to access only if this is the correct bank
          when (snoop_bank === i.U) {
            map.bits.states(snoop_offset) := true.B
          }
        }
        entry_existence_vec(i*entries_per_way + j) := io.snoop.valid && map.valid && (map.bits.tag === snoop_tag) && snoop_bank === i.U
      }
    }
    //iirc this should be synthesizable
    //mux might be better code writing, should be okay
    /*for (i <- 0 until (params.ways * entries_per_way) - 1) {
      // or everything
      entry_existence := entry_existence_vec.reduce(_ || _)
    }*/
    entry_existence := entry_existence_vec.reduce(_ || _)
    //if entry not in table, add new entry
    when (!entry_existence && io.snoop.valid) {
      memory_access_map_table(snoop_bank).bits.maps(head(snoop_bank)).bits.states := initial_state_vec
      memory_access_map_table(snoop_bank).bits.maps(head(snoop_bank)).bits.tag := snoop_tag
      memory_access_map_table(snoop_bank).bits.maps(head(snoop_bank)).valid := true.B
      head(snoop_bank) := head(snoop_bank) + 1.U //overflow is supposed to occur, wrap around back to front of table
    }
    
    prefetch_input := tag_candidate(snoop_bank)

    //pattern matching
    // long crit path for now, can fix later
    val forward_map = Wire(UInt((params.N).W))
    val backward_map = Wire(UInt((params.N).W))
    val logic_pos = Wire(Vec(max_k, UInt()))
    val logic_neg = Wire(Vec(max_k, UInt()))
    val and_back_pos = Wire(Vec(max_k, UInt()))
    val and_back_pos_uint = Wire(UInt(max_k.W))
    val and_back_neg = Wire(Vec(max_k, UInt()))
    val and_back_neg_uint = Wire(UInt(max_k.W))
    val delta_uint = Wire(UInt(max_k.W))

    //TODO: Clean up
    val forward_map_reverse_broken = Wire(UInt((params.N).W))
    val shifty = Wire(UInt(line_bits.W))
    shifty := params.N.U - snoop_offset - 1.U
    forward_map_reverse_broken := prefetch_input << (shifty)
    forward_map := Reverse(forward_map_reverse_broken)
    backward_map := prefetch_input >> snoop_offset

    for (k <- 0 until max_k) {
      logic_pos(k) := forward_map(k) & (forward_map(2*k) | forward_map((2*k)+1))
      logic_neg(k) := backward_map(k) & (backward_map(2*k) | backward_map((2*k)+1))
    }
    for (i <- 0 until max_k) {
      and_back_pos(i) := logic_pos(i) & !backward_map(i)
      and_back_neg(i) := logic_neg(i) & !forward_map(i)
    }
    and_back_pos_uint := and_back_pos.asUInt()
    and_back_neg_uint := and_back_neg.asUInt()
    //Prioritize positive delta
    delta_uint := Mux(and_back_pos_uint =/= 0.U, and_back_pos_uint, and_back_neg_uint)

    //Buffer prefetches using queue
    val pref_out = Reg(Output(Flipped(Decoupled(new prefetch_req))))
    val queue_out = Reg(Input(Flipped(Decoupled(new prefetch_req))))
    val prefetch_active = RegInit(false.B)
    val reset_deq = RegInit(true.B) 
    val prefetch_queue = Module(new Queue(new prefetch_req, entries=8, flow=true))

    prefetch_queue.io.enq <> pref_out
    prefetch_queue.io.deq <> queue_out

    //Add generated prefetch to queue
    when (delta_uint =/= 0.U && io.snoop.valid) {
      when (and_back_pos_uint =/= 0.U) { 
        //Positive delta was selected
        pref_out.bits.addr := io.snoop.bits.block_address + (PriorityEncoder(delta_uint) << log2Up(io.snoop.bits.blockBytes))
      } .otherwise {
        //Negative delta was selected
        pref_out.bits.addr := io.snoop.bits.block_address - (PriorityEncoder(delta_uint) << log2Up(io.snoop.bits.blockBytes))
      }
      pref_out.bits.write := io.snoop.bits.write
      pref_out.valid := true.B
      prefetch_active := true.B
    } .otherwise {
      pref_out.valid := false.B
    }

    io.request.bits.address := prefetch_queue.io.deq.bits.addr
    io.request.valid := prefetch_active
    io.request.bits.write := prefetch_queue.io.deq.bits.write

    //Dequeue
    when (io.request.fire()) {
      prefetch_queue.io.deq.ready := true.B
      reset_deq := true.B
      prefetch_active := false.B
    }

    when (reset_deq) {
      //reset dequeue ready
      prefetch_queue.io.deq.ready := false.B
      reset_deq := false.B
    }


}


//N is num cache lines per zone
class MemoryAccessMap(val N: Int) extends Bundle {
  // false -> init, true -> access
  val states = Vec(N, Bool())
  val tag = UInt() // top bits of mem address
}

// a vec of vecs of vecs
// TODO: fix params
class MemoryAccessMapBank(val entries_per_way: Int, val N: Int) extends Bundle {
  val maps = Vec(entries_per_way, Valid(new MemoryAccessMap(N)))
}

class prefetch_req extends Bundle {
  val addr = UInt()
  val write = Bool()
}

/*class MemoryAccessMapEntry extends Bundle {
    val addr = UInt()
    val state = UInt(2.W) // should probs use an enum
}*/
