package simpleRoCC.example

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.IdRange
import freechips.rocketchip.tilelink.{IDMapGenerator, TLClientNode, TLMasterParameters, TLMasterPortParameters}
import freechips.rocketchip.util.TwoWayCounter
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import simpleRoCC.XLen

/**   - 0 : Control Register
  *   - 1 : Status Register
  *   - 2 : Interrupt Clear Register
  *   - 3 : Source Address Register
  *   - 4 : Destination Address Register
  *   - 5 : Length Register
  */

class Control extends Bundle {
  val go = Bool()
  // val interruptEnable = Bool()

  def rd = 0.U

  def wr(v: UInt) = {
    val r = Wire(new Control)
    r.go := v(0) // 1 to launch DMA transaction. 0 ignored
    // interruptEnable := v(1) // 1 to enable interrupts, 0 to disable interrupts
    r
  }
}

class Status extends Bundle {
  val busy    = Bool()
  val done    = Bool()
  val corrupt = Bool() // TL-D corrupt
  val denied  = Bool() // TL-D denied

  def wr(v: UInt) = {}
  def rd: UInt =
    Cat(denied, corrupt, done, busy)
}

class Clear extends Bundle {
  val clear = Bool()

  def rd = 0.U

  def wr(v: UInt) = {
    val r = Wire(new Clear)
    r.clear := v(0) // 1 to clear pending interrupt, 0 is ignored
    r
  }
}

class DMAConfig(val xLen: Int, val addrWidth: Int) extends Module {

  val io = IO(new simpleRoCC.SimpleRoCCCoreIO(xLen, addrWidth))

  val dmaCtrl = IO(new Bundle {
    val descriptor = Valid(new DMADescriptor(addrWidth))
    val done       = Input(Bool())
    val denied     = Input(Bool())
    val corrupt    = Input(Bool())
  })

  val cmd     = Queue(io.cmd)
  val funct   = cmd.bits.inst.funct
  val regAddr = cmd.bits.rs2(2, 0)
  val isClear = regAddr === 2.U
  val isCtrl  = regAddr === 0.U
  val data    = cmd.bits.rs1
  val doWrite = !cmd.bits.inst.xd
  val doRead  = cmd.bits.inst.xd

  val descriptorValid = RegInit(false.B)
  val srcAddrReg      = RegEnable(data, !descriptorValid && cmd.valid && doWrite && regAddr === 3.U)
  val dstAddrReg      = RegEnable(data, !descriptorValid && cmd.valid && doWrite && regAddr === 4.U)
  val byteLengthReg   = RegEnable(data, !descriptorValid && cmd.valid && doWrite && regAddr === 5.U)

  val deniedStatusReg  = RegInit(false.B)
  val corruptStatusReg = RegInit(false.B)
  val doneStatusReg    = RegEnable(true.B, false.B, dmaCtrl.done)

  dmaCtrl.descriptor.valid            := false.B
  dmaCtrl.descriptor.bits.baseSrcAddr := srcAddrReg
  dmaCtrl.descriptor.bits.baseDstAddr := dstAddrReg
  dmaCtrl.descriptor.bits.byteLength  := byteLengthReg

  val ctrlWr = Wire(new Control)
  ctrlWr.go := descriptorValid
  val statusWr = Wire(new Status)
  statusWr.busy    := descriptorValid && !doneStatusReg
  statusWr.done    := doneStatusReg
  statusWr.corrupt := corruptStatusReg
  statusWr.denied  := deniedStatusReg
  val clearWr = Wire(new Clear)
  clearWr.clear := (new Clear).wr(data).clear

  when(cmd.valid && isCtrl) {
    when((new Control).wr(data).go) {
      assert(!descriptorValid, "New DMA request received before completing the previous DMA")
      descriptorValid          := true.B
      dmaCtrl.descriptor.valid := true.B
    }
  }

  when(cmd.valid && isClear && clearWr.clear) {
    descriptorValid  := false.B
    doneStatusReg    := false.B
    deniedStatusReg  := false.B
    corruptStatusReg := false.B
  }.otherwise {
    deniedStatusReg  := deniedStatusReg | dmaCtrl.denied
    corruptStatusReg := corruptStatusReg | dmaCtrl.corrupt
  }

  val respData = MuxLookup(regAddr, 0.U)(
    Seq(
      0.U -> ctrlWr.rd,
      1.U -> statusWr.rd,
      2.U -> clearWr.rd,
      3.U -> srcAddrReg,
      4.U -> dstAddrReg,
      5.U -> byteLengthReg,
    ),
  )

  when(cmd.fire) {
    printf(cf"New Command: ${cmd.bits.inst}\n")
  }

  cmd.ready := doRead || (doWrite && (!descriptorValid || isClear))

  val doResp = cmd.bits.inst.xd
  io.resp.valid     := cmd.valid && doResp
  io.resp.bits.rd   := cmd.bits.inst.rd
  io.resp.bits.data := respData

  io.busy      := !cmd.ready
  io.interrupt := false.B

  io.mem.req.valid := false.B
  io.mem.req.bits  := DontCare
}

class DMA(
  val inFlight:  Int,
  val addrWidth: Int,
)(
  implicit p: Parameters,
) extends LazyModule {

  val xLen = p(XLen)

  val rdClient = new TLClientNode(
    Seq(
      TLMasterPortParameters.v1(
        Seq(
          TLMasterParameters.v1(
            name = "DMARdCtrl",
            sourceId = IdRange(0, inFlight),
          ),
        ),
      ),
    ),
  )

  val wrClient = new TLClientNode(
    Seq(
      TLMasterPortParameters.v1(
        Seq(
          TLMasterParameters.v1(
            name = "DMAWrCtrl",
            sourceId = IdRange(0, inFlight),
          ),
        ),
      ),
    ),
  )

  lazy val module = new DMAImp(this)
}

class DMADescriptor(addrWidth: Int) extends Bundle {
  val baseSrcAddr = UInt(addrWidth.W)
  val baseDstAddr = UInt(addrWidth.W)
  val byteLength  = UInt(16.W)
}

class DMAImp(outer: DMA) extends LazyModuleImp(outer) {

  val io = IO(new simpleRoCC.SimpleRoCCCoreIO(outer.xLen, outer.addrWidth))

  val config = Module(new DMAConfig(outer.xLen, outer.addrWidth))
  config.io <> io

  val busy    = RegInit(false.B)
  val rdBytes = Reg(UInt(16.W))
  val wrBytes = Reg(UInt(16.W))

  when(config.dmaCtrl.descriptor.valid && !busy) {
    busy    := true.B
    rdBytes := 0.U
    wrBytes := 0.U
  }

  val (rdOut, rdEdge) = outer.rdClient.out(0)
  val (wrOut, wrEdge) = outer.wrClient.out(0)

  /* The read response data from source are forwarded to the destination as write request data with out storing.
   * So we need the transfer size of the destination greater-than or equal to the source interconnect.
   */
  require(wrEdge.manager.beatBytes == rdEdge.manager.beatBytes)
  require(wrEdge.manager.maxTransfer >= rdEdge.manager.maxTransfer)

  /* Note that, DMA performs
   *  1. Send a read request to source (Channel-A)
   *  2. Receive a read response from source (Channel-D)
   *  3. Transform the read response as write request to destination (Channel-A)
   *  4. Receive a write acknowledge from destination (Channel-D)
   *
   * In the current design we have assumed that source and destination are connected through different ports.
   * If they share same link down the network topology it may lead to dead-lock due to cyclic dependencies.
   * To avoid this deadlock, we need to ensure that we have enough buffer to receive all the source read responses
   * before sending a request.
   *
   */

  val rd_a_q = Module(new Queue(rdOut.a.bits.cloneType, entries = 2))
  val rd_d_q = Module(new Queue(rdOut.d.bits.cloneType, entries = outer.inFlight))
  rdOut.a <> rd_a_q.io.deq
  rd_d_q.io.enq <> rdOut.d

  val wr_a_q = Module(new Queue(wrOut.a.bits.cloneType, entries = 2))
  val wr_d_q = Module(new Queue(wrOut.d.bits.cloneType, entries = 2))
  wrOut.a <> wr_a_q.io.deq
  wr_d_q.io.enq <> wrOut.d

  val rd_a                                 = rd_a_q.io.enq
  val (rd_a_first, rd_a_last, rd_req_done) = rdEdge.firstlast(rd_a)

  val rd_d                                  = rd_d_q.io.deq
  val (rd_d_first, rd_d_last, rd_resp_done) = rdEdge.firstlast(rd_d)

  val wr_a                                 = wr_a_q.io.enq
  val (wr_a_first, wr_a_last, wr_req_done) = wrEdge.firstlast(wr_a)

  val wr_d                                  = wr_d_q.io.deq
  val (wr_d_first, wr_d_last, wr_resp_done) = wrEdge.firstlast(wr_d)

  val idMap      = Module(new IDMapGenerator(outer.inFlight))
  val wrInFlight = TwoWayCounter(wr_a.fire, wr_d.fire, outer.inFlight)
  val srcId      = idMap.io.alloc.bits

  val rdPending = rdBytes =/= config.dmaCtrl.descriptor.bits.byteLength
  val wrPending = wrBytes =/= config.dmaCtrl.descriptor.bits.byteLength

  idMap.io.alloc.ready := rdPending && rd_a.ready && busy // Hold source-Id until the read request beat is sent
  idMap.io.free.valid  := wr_resp_done                    // Free source-Id after receiving the wr resp. beat associated with it
  idMap.io.free.bits   := wr_d.bits.source

  // -- Generate read requests to the source address ----

  val maxTransfer = rdEdge.manager.maxTransfer
  val nextRdAddr  = config.dmaCtrl.descriptor.bits.baseSrcAddr + rdBytes
  val leftBytes   = config.dmaCtrl.descriptor.bits.byteLength - rdBytes
  val lgSize      = Mux(leftBytes >= maxTransfer.U, log2Ceil(maxTransfer).U, Log2(leftBytes))

  when(rd_a.fire) {
    rdBytes := rdBytes + (1.U(16.W) << rd_a.bits.size)
  }

  val (rdLegal, rdReq) = rdEdge.Get(srcId, nextRdAddr, lgSize)
  rd_a.valid := busy && rdPending && idMap.io.alloc.valid
  when(rd_a.valid) {
    assert(rdLegal)
  }
  rd_a.bits := rdReq

  // -- Generate write requests to the destination address ----

  val nextWrAddr       = config.dmaCtrl.descriptor.bits.baseDstAddr + wrBytes
  val (wrLegal, wrReq) = wrEdge.Put(rd_d.bits.source, nextWrAddr, rd_d.bits.size, rd_d.bits.data)
  wr_a.valid := rd_d.valid
  wr_a.bits  := wrReq
  rd_d.ready := wr_a.ready

  wr_d.ready := true.B
  when(wr_a.fire && wr_a_last) {
    wrBytes := wrBytes + (1.U(16.W) << wr_a.bits.size)
  }

  config.dmaCtrl.corrupt := (rd_d.bits.corrupt && rd_d.valid) || (wr_d.bits.corrupt && wr_d.valid)
  config.dmaCtrl.denied  := (rd_d.bits.denied && rd_d.valid) || (wr_d.bits.denied && wr_d.valid)
  config.dmaCtrl.done    := false.B
  when(busy && !wrPending && wrInFlight === 0.U) {
    config.dmaCtrl.done := true.B
    busy                := false.B
  }

}
