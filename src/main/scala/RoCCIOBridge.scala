package simpleRoCC

import chisel3._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.subsystem.HierarchicalElementCrossingParamsLike
import freechips.rocketchip.tile.{
  CustomCSR,
  LazyRoCC,
  LazyRoCCModuleImp,
  LookupByHartIdImpl,
  OpcodeSet,
  RocketTile,
  RocketTileModuleImp,
  RocketTileParams,
  TileVisibilityNodeKey,
  XLen,
}
import freechips.rocketchip.tilelink.{
  TLClientNode,
  TLManagerNode,
  TLMasterParameters,
  TLMasterPortParameters,
  TLSlaveParameters,
  TLSlavePortParameters,
}
import org.chipsalliance.cde.config.{Field, Parameters}

case object InsertRoCCIO extends Field[Boolean](false)

class RoCCIOBridgeTop(opcodes: OpcodeSet = OpcodeSet.custom0, roccCSRs: Seq[CustomCSR] = Nil)(implicit p: Parameters)
  extends RoCCIOBridge(opcodes, roccCSRs = roccCSRs) {

  val dummySlaveParams = TLSlaveParameters.v1(
    address = Seq(AddressSet(BigInt(0x80000000L), 0xfff)),
    regionType = RegionType.UNCACHED,
    supportsPutFull = TransferSizes(1, 64),
    supportsGet = TransferSizes(1, 64),
  )

  val dummySlave  = TLManagerNode(Seq(TLSlavePortParameters.v1(Seq(dummySlaveParams), beatBytes = 4)))
  val dummyMaster = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1(name = "dummyMaster")))))

  DisableMonitors { implicit p =>
    /* Dummy Slave and Sink, that emulates intra-tile master and the rest of the system it communicates with.
       The tile see the rest of the system through "p(TileVisibiltyNodeKey)". Since RoCC Accelerator is part of the Tile,
       any tile-link master interface of the RoCC accelerator must connect to other slaves in the rest of the system through
       p(TileVisibilityNodeKey). In a standalone RoCC accelerator module, without any TL master interface, we will still need
       to know p(TileVisibilityNodeKey) for determining the physical address bit-width, to interface with L1DCache.
     */
    dummySlave := p(TileVisibilityNodeKey) := dummyMaster
  }

  override lazy val module = new RoCCIOBridgeTopImp(this)
}

class RoCCIOBridgeTopImp(outer: RoCCIOBridgeTop) extends RoCCIOBridgeImp(outer) {}

class RoCCIOBridge(opcodes: OpcodeSet = OpcodeSet.custom0, roccCSRs: Seq[CustomCSR] = Nil)(implicit p: Parameters)
  extends LazyRoCC(opcodes, roccCSRs = roccCSRs) {

  override lazy val module = new RoCCIOBridgeImp(this)
}

class RoCCIOBridgeImp(outer: RoCCIOBridge) extends LazyRoCCModuleImp(outer) {
  val xLen      = outer.p(XLen)
  val addrWidth = io.mem.req.bits.addr.getWidth
  val tagWidth  = 5

  val roccifc = IO(Flipped(new SimpleRoCCCoreIO(xLen, addrWidth, tagWidth)))
  println(s"${io.mem.req.bits.addr.getWidth}  >= ${roccifc.mem.req.bits.addr.getWidth}")
  require(io.mem.req.bits.tag.getWidth >= roccifc.mem.req.bits.tag.getWidth)
  require(io.csrs.length == roccifc.csrs.length)




  io.mem.req <> roccifc.mem.req
  roccifc.mem.resp.valid     := io.mem.resp.valid
  roccifc.mem.resp.bits.data := io.mem.resp.bits.data
  roccifc.mem.resp.bits.mask := io.mem.resp.bits.mask
  roccifc.mem.resp.bits.tag  := io.mem.resp.bits.tag

  io.cmd <> roccifc.cmd
  io.resp <> roccifc.resp
  io.busy           := roccifc.busy
  io.interrupt      := roccifc.interrupt
  roccifc.exception := io.exception
  roccifc.csrs <> io.csrs
}

class RocketTileWithRoCCIO(
  params:   RocketTileParams,
  crossing: HierarchicalElementCrossingParamsLike,
  lookup:   LookupByHartIdImpl,
)(
  implicit p: Parameters,
) extends RocketTile(params, crossing, lookup) {
  override lazy val module = new RocketTileWithRoCCIOImp(this)
}

class RocketTileWithRoCCIOImp(outer: RocketTileWithRoCCIO) extends RocketTileModuleImp(outer) {
  val addrWidth = outer.p(XLen)
  val tagWidth  = 5

  val roccifc = if (p(InsertRoCCIO)) Some(IO(Flipped(new SimpleRoCCCoreIO(xLen, addrWidth, tagWidth)))) else None

  roccifc.map { i =>
    val ioBridge = outer.roccs(0).module.asInstanceOf[RoCCIOBridgeImp]
    ioBridge.roccifc.busy := i.busy
    ioBridge.roccifc.cmd <> i.cmd
    ioBridge.roccifc.resp <> i.resp
    ioBridge.roccifc.mem <> i.mem
    ioBridge.roccifc.interrupt := i.interrupt
    i.exception                := ioBridge.roccifc.exception
  }
}
