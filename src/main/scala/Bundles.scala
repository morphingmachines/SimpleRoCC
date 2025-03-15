package simpleRoCC

import chisel3._
import chisel3.util._
import freechips.rocketchip.rocket._
import freechips.rocketchip.tile.RoCCInstruction

class SimpleRoCCCommand(xLen: Int) extends Bundle {
  val inst   = new RoCCInstruction
  val rs1    = Bits(xLen.W)
  val rs2    = Bits(xLen.W)
  val status = new MStatus
}

class SimpleRoCCResponse(xLen: Int) extends Bundle {
  val rd   = Bits(5.W)
  val data = Bits(xLen.W)
}

class SimpleHellaCacheReq(xLen: Int, addrWidth: Int, tagWidth: Int) extends Bundle {
  val dataBytes = xLen / 8

  val addr   = UInt(addrWidth.W)
  val tag    = UInt(tagWidth.W)
  val cmd    = UInt(M_SZ.W)
  val size   = UInt(log2Ceil(log2Ceil(dataBytes) + 1).W)
  val signed = Bool()
  val dprv   = UInt(PRV.SZ.W)
  val dv     = Bool()

  val data = UInt(xLen.W)
  val mask = UInt(dataBytes.W)

  val phys     = Bool()
  val no_alloc = Bool()
  val no_xcpt  = Bool()
}

class SimpleHellaCacheResp(xLen: Int, tagWidth: Int) extends Bundle {
  val dataBytes = xLen / 8

  val tag  = UInt(tagWidth.W)
  val data = UInt(xLen.W)
  val mask = UInt(dataBytes.W)
}

class SimpleHellaCacheIO(xLen: Int, addrWidth: Int, tagWidth: Int) extends Bundle {
  val req  = Decoupled(new SimpleHellaCacheReq(xLen, addrWidth, tagWidth))
  val resp = Flipped(Valid(new SimpleHellaCacheResp(xLen, tagWidth)))
}

class SimpleCustomCSRIO(xLen: Int) extends Bundle {
  val ren   = Output(Bool())       // set by CSRFile, indicates an instruction is reading the CSR
  val wen   = Output(Bool())       // set by CSRFile, indicates an instruction is writing the CSR
  val wdata = Output(UInt(xLen.W)) // wdata provided by instruction writing CSR
  val value = Output(UInt(xLen.W)) // current value of CSR in CSRFile

  val stall = Input(Bool()) // reads and writes to this CSR should stall (must be bounded)

  val set   = Input(Bool()) // set/sdata enables external agents to set the value of this CSR
  val sdata = Input(UInt(xLen.W))
}

class SimpleRoCCCoreIO(xLen: Int, addrWidth: Int, tagWidth: Int = 0, nRoCCCSRs: Int = 0) extends Bundle {
  val cmd       = Flipped(Decoupled(new SimpleRoCCCommand(xLen)))
  val resp      = Decoupled(new SimpleRoCCResponse(xLen))
  val mem       = new SimpleHellaCacheIO(xLen, addrWidth, tagWidth)
  val busy      = Output(Bool())
  val interrupt = Output(Bool())
  val exception = Input(Bool())
  val csrs      = Flipped(Vec(nRoCCCSRs, new SimpleCustomCSRIO(xLen)))
}
