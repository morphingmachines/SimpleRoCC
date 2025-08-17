package simpleRoCC

import freechips.rocketchip.diplomacy.{AddressSet, RegionType, TransferSizes}
import freechips.rocketchip.tile.{AccumulatorExample, OpcodeSet, TileVisibilityNodeKey}
import freechips.rocketchip.tilelink.{
  TLClientNode,
  TLManagerNode,
  TLMasterParameters,
  TLMasterPortParameters,
  TLSlaveParameters,
  TLSlavePortParameters,
}
import org.chipsalliance.cde.config.Parameters

class AccumulatorWrapper(opcodes: OpcodeSet = OpcodeSet.custom0)(implicit p: Parameters)
  extends AccumulatorExample(opcodes) {

  val dummySlaveParams = TLSlaveParameters.v1(
    address = Seq(AddressSet(BigInt(0x80000000L), 0xfff)),
    regionType = RegionType.UNCACHED,
    supportsPutFull = TransferSizes(1, 64),
    supportsGet = TransferSizes(1, 64),
  )

  val dummySlave  = TLManagerNode(Seq(TLSlavePortParameters.v1(Seq(dummySlaveParams), beatBytes = 4)))
  val dummyMaster = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1(name = "dummyMaster")))))

  /* Dummy Slave and Sink, that emulates intra-tile master and the rest of the system it communicates with.
       The tile see the rest of the system through "p(TileVisibiltyNodeKey)". Since RoCC Accelerator is part of the Tile,
       any tile-link master interface of the RoCC accelerator must connect to other slaves in the rest of the system through
       p(TileVisibilityNodeKey). In a standalone RoCC accelerator module, without any TL master interface, we will still need
       to know p(TileVisibilityNodeKey) for determining the physical address bit-width, to interface with L1DCache.
   */
  dummySlave := p(TileVisibilityNodeKey) := dummyMaster

}
