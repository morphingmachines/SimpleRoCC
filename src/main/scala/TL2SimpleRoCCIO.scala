package simpleRoCC

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper.{RegField, RegFieldDesc, RegFieldGroup}
import freechips.rocketchip.resources.SimpleDevice
import freechips.rocketchip.rocket._
import freechips.rocketchip.tile.RoCCInstruction
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule.{InModuleBody, LazyModule, LazyModuleImp}

case class SimpleRoCCParams(xLen: Int, addrWidth: Int, tagWidth: Int = 0, nRoCCCSRs: Int = 0)

class TLRegister2RoCC(val base: BigInt, val roccParams: SimpleRoCCParams)(implicit p: Parameters) extends LazyModule {
  val device = new SimpleDevice("SimRoCC", Seq(""))

  val regNode = TLRegisterNode(
    address = Seq(AddressSet(base, 0xfff)),
    device = device,
    beatBytes = 32,
    concurrency = 1,
  )

  lazy val module = new TLRegister2RoCCImp(this)
}

class TLRegister2RoCCImp(outer: TLRegister2RoCC) extends LazyModuleImp(outer) {
  val params = outer.roccParams
  require(params.xLen == 32 || params.xLen == 64)
  require(params.tagWidth <= 5)
  val rocc = IO(
    Flipped(
      new SimpleRoCCCoreIO(
        xLen = params.xLen,
        addrWidth = params.addrWidth,
        tagWidth = params.tagWidth,
        nRoCCCSRs = params.nRoCCCSRs,
      ),
    ),
  )

  require(params.nRoCCCSRs <= 32, "TLRegisterNode memory-map can accommodate only 32 CSRs.")

  rocc.exception := false.B

  class RoCCMemReq extends Bundle {
    val dataBytes = params.xLen / 8

    val addr = UInt(params.addrWidth.W)
    val tag  = UInt(params.tagWidth.W)
    val cmd  = UInt(M_SZ.W)
    val size = UInt(log2Ceil(log2Ceil(dataBytes) + 1).W)
    val data = UInt(params.xLen.W)
    val mask = UInt(dataBytes.W)

    def pack160 = {
      val rAddr = addr.pad(64)
      val rData = data.pad(64)
      val rInfo = Cat(tag.pad(8), mask.pad(8), size.pad(8), cmd.pad(8))
      Cat(rInfo.pad(32), rAddr, rData)
    }

  }

  class RoCCMemResp extends Bundle {
    val dataBytes = params.xLen / 8

    val data = UInt(params.xLen.W)
    val mask = UInt(dataBytes.W)
    val tag  = UInt(params.tagWidth.W)

    def unpack80(d: UInt) = {
      val r = Wire(new RoCCMemResp)
      r.data := d.take(64)
      r.mask := d(71, 64)
      r.tag  := d(79, 72)
      r
    }
  }

  val roccCmdReqBuf = Module(new Queue(UInt(160.W), 2))
  rocc.cmd.valid             := roccCmdReqBuf.io.deq.valid
  roccCmdReqBuf.io.deq.ready := rocc.cmd.ready
  rocc.cmd.bits.inst         := roccCmdReqBuf.io.deq.bits.take(32).asTypeOf(new RoCCInstruction)
  rocc.cmd.bits.status       := DontCare
  if (params.xLen == 64) {
    rocc.cmd.bits.rs1 := roccCmdReqBuf.io.deq.bits(95, 32)
    rocc.cmd.bits.rs2 := roccCmdReqBuf.io.deq.bits(159, 96)
  } else {
    rocc.cmd.bits.rs1 := roccCmdReqBuf.io.deq.bits(63, 32)
    rocc.cmd.bits.rs2 := roccCmdReqBuf.io.deq.bits(127, 96)
  }

  val roccCmdRespBuf = Seq.fill(32)(Module(new Queue(UInt(params.xLen.W), 1)))
  for (i <- 0 until 32) {
    roccCmdRespBuf(i).io.enq.valid := rocc.resp.valid && rocc.resp.bits.rd === i.U
    roccCmdRespBuf(i).io.enq.bits  := rocc.resp.bits.data
    rocc.resp.ready := roccCmdRespBuf.zipWithIndex.map { case (x, i) =>
      (x.io.enq.ready) && (rocc.resp.bits.rd === i.U)
    }.reduce(_ || _)
  }

  val csr = Seq.fill(params.nRoCCCSRs)(RegInit(0.U(params.xLen.W)))

  for (i <- 0 until params.nRoCCCSRs) {
    rocc.csrs(i).value := csr(i)
    when(rocc.csrs(i).set) {
      csr(i) := rocc.csrs(i).sdata
    }.elsewhen(rocc.csrs(i).wen) {
      csr(i) := rocc.csrs(i).wdata
    }
  }

  val roCCMemReqBuf = Module(new Queue(new RoCCMemReq, 8))
  roCCMemReqBuf.io.enq.valid     := rocc.mem.req.valid
  rocc.mem.req.ready             := roCCMemReqBuf.io.enq.ready
  roCCMemReqBuf.io.enq.bits.addr := rocc.mem.req.bits.addr
  roCCMemReqBuf.io.enq.bits.data := rocc.mem.req.bits.data
  roCCMemReqBuf.io.enq.bits.tag  := rocc.mem.req.bits.tag
  roCCMemReqBuf.io.enq.bits.mask := rocc.mem.req.bits.mask
  roCCMemReqBuf.io.enq.bits.size := rocc.mem.req.bits.size
  roCCMemReqBuf.io.enq.bits.cmd  := rocc.mem.req.bits.cmd

  val roCCMemRespBuf = Module(new Queue(new RoCCMemResp, 8))
  rocc.mem.resp.valid         := roCCMemRespBuf.io.deq.valid
  roCCMemRespBuf.io.deq.ready := true.B
  rocc.mem.resp.bits.data     := roCCMemRespBuf.io.deq.bits.data
  rocc.mem.resp.bits.mask     := roCCMemRespBuf.io.deq.bits.mask
  rocc.mem.resp.bits.tag      := roCCMemRespBuf.io.deq.bits.tag

  val roccBusyReg = RegNext(rocc.busy)
  val csrStallReg = rocc.csrs.map(i => RegNext(i.stall))
  require(csrStallReg.length <= 32)
  val roccStatus = Cat(roccBusyReg.asUInt, Cat(csrStallReg.map(_.asUInt).reverse.toSeq).pad(32)).pad(64)

  def roccCmdRespRdFunc(i: Int)(iValid_Oready: Bool): (Bool, UInt) = {

    roccCmdRespBuf(i).io.deq.ready := iValid_Oready
    (
      true.B,
      roccCmdRespBuf(i).io.deq.bits.pad(64),
    )
  }

  def roCCMemReqRdFunc(ready: Bool): (Bool, UInt) = {
    roCCMemReqBuf.io.deq.ready := ready
    (true.B, Cat(roCCMemReqBuf.io.deq.valid.asUInt, roCCMemReqBuf.io.deq.bits.pack160).pad(192))
  }

  def roCCMemRespWrFunc(valid: Bool, data: UInt): (Bool) = {
    roCCMemRespBuf.io.enq.valid := valid
    roCCMemRespBuf.io.enq.bits  := roCCMemRespBuf.io.enq.bits.unpack80(data)
    roCCMemRespBuf.io.enq.ready
  }

  def csrRdFunc(i: Int)(valid: Bool): (Bool, UInt) = {
    rocc.csrs(i).ren := valid
    (!rocc.csrs(i).stall, csr(i).pad(64))
  }

  def csrWrFunc(i: Int)(valid: Bool, data: UInt): Bool = {
    rocc.csrs(i).wen   := valid
    rocc.csrs(i).wdata := data
    !rocc.csrs(i).stall
  }

  outer.regNode.regmap(
    0x000 -> RegFieldGroup(
      "RoccCSR",
      Some("RoCC CSR Registers"),
      csr.zipWithIndex.flatMap { case (_, i) =>
        RegField(64, csrRdFunc(i)(_), csrWrFunc(i)(_, _), Some(RegFieldDesc(s"RoCCCSR$i", ""))) :: RegField(192) :: Nil
      },
    ),
    0x100 -> RegFieldGroup(
      "RoCCHellaCacheIfc",
      Some("RoCC Hella Cache Interface"),
      Seq(
        RegField.r(
          192,
          roCCMemReqRdFunc(_),
          RegFieldDesc("RequestIfc", "BitFields:{zero:31, valid:1, tag:8, mask:8, size:8, cmd:8, addr:64, data:64}"),
        ),
        RegField(64),
        RegField.w(80, roCCMemRespWrFunc(_, _), RegFieldDesc("ResponseIfc", "BitFields:{tag:8 ,mask:8, data:64}")),
        RegField(176),
      ),
    ),
    0x140 -> RegFieldGroup(
      "RoCCCSRStallStatus",
      Some("RoCC CSR Stall Status"),
      csrStallReg.zipWithIndex.flatMap { case (x, i) =>
        RegField.r(1, x, RegFieldDesc(s"RoCCCSR${i}_Stall", "")) :: Nil
      } ++
        Seq(RegField(32 - csrStallReg.length)),
    ),
    0x144 -> RegFieldGroup(
      "RoCCBusyStatus",
      None,
      Seq(RegField.r(1, roccBusyReg, RegFieldDesc("RoCCBusy", ""))),
    ),
    0x180 -> RegFieldGroup(
      "RoCCCommandRequestIfc",
      Some("RoCC Command Request Interface"),
      Seq(
        RegField.w(
          160,
          roccCmdReqBuf.io.enq,
          RegFieldDesc("RoCCCmdReq", "BitFields:{rs2-value:64, rs1-value:64, rocc-instruction:32}"),
        ),
      ),
    ),
    0x200 -> RegFieldGroup(
      "RoCCCommandResponse",
      Some(
        "RoCC Command response. Each tag has its own address. Previous response must be read to receive next response.",
      ),
      roccCmdRespBuf.zipWithIndex.flatMap { case (_, i) =>
        RegField.r(64, roccCmdRespRdFunc(i)(_), RegFieldDesc(s"CmdResponse$i", s"RoCC Command response with tag-$i")) ::
          RegField.r(1, roccCmdRespBuf(i).io.deq.valid, RegFieldDesc(s"CmdResponse$i-valid", "")) :: RegField(
            191,
          ) :: Nil
      },
    ),
  )
}

class TL2RoCCWrapper(implicit p: Parameters) extends LazyModule {
  val clientNode = TLClientNode(
    Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1(name = "TLReg2Rocc", sourceId = IdRange(0, 1))))),
  )

  val adapter = LazyModule(
    new TLRegister2RoCC(base = 0, roccParams = SimpleRoCCParams(xLen = 32, addrWidth = 32, tagWidth = 5, nRoCCCSRs = 3)),
  )

  adapter.regNode := clientNode

  val tl2Rocc     = InModuleBody(clientNode.makeIOs())
  lazy val module = new TL2RoCCWrapperImp(this)
}

class TL2RoCCWrapperImp(outer: TL2RoCCWrapper) extends LazyModuleImp(outer) {
  val io = IO(
    Flipped(
      new SimpleRoCCCoreIO(
        xLen = 32,
        addrWidth = 32,
        tagWidth = 5,
        nRoCCCSRs = 3,
      ),
    ),
  )

  io <> outer.adapter.module.rocc
}
