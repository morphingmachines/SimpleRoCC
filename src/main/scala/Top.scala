package simpleRoCC

import emitrtl._
import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.tile.OpcodeSet

/** To run from a terminal shell
  * {{{
  * mill ce.runMain ce.main CE
  * }}}
  */

object Main extends App with LazyToplevel {
  import org.chipsalliance.cde.config.{Parameters, Config}
  val str = if (args.length == 0) "" else args(0)
  val lazyTop = str match {
    case "RV32"       => LazyModule(new example.CeTop()(new Config(new RV32Config)))
    case "RV32RoCCIO" => LazyModule(new example.CeWithRoCCDMA()(new Config(new RV32WithRoCCIOConfig)))
    case "RV64RoCCIO" => LazyModule(new example.CeWithRoCCDMA()(new Config(new RV64WithRoCCIOConfig)))
    case "RoCCIO" => {
      import freechips.rocketchip.tile.TileVisibilityNodeKey
      import freechips.rocketchip.tilelink.TLEphemeralNode
      import freechips.rocketchip.diplomacy.ValName
      val p: Parameters =
        (new Config(new RV32Config)).alterMap(Map(TileVisibilityNodeKey -> TLEphemeralNode()(ValName("tile_master"))))
      LazyModule(new simpleRoCC.RoCCIOBridgeTop()(p))
    }
    case "AccumAccel" => {
      import freechips.rocketchip.tile.TileVisibilityNodeKey
      import freechips.rocketchip.tilelink.TLEphemeralNode
      import freechips.rocketchip.diplomacy.ValName
      val p: Parameters =
        (new Config(new RV32Config)).alterMap(Map(TileVisibilityNodeKey -> TLEphemeralNode()(ValName("tile_master"))))
      LazyModule(new AccumulatorWrapper(OpcodeSet.custom0)(p))
    }
    case _ => throw new Exception("Unknown Module Name!")
  }

  showModuleComposition(lazyTop)
  chisel2firrtl()
  firrtl2sv()
  genDiplomacyGraph()

}
