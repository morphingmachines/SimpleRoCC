package simpleRoCC

import org.chipsalliance.diplomacy.lazymodule._
import freechips.rocketchip.rocket.{DCacheParams, ICacheParams, MulDivParams, RocketCoreParams}
import freechips.rocketchip.subsystem.CacheBlockBytes
import freechips.rocketchip.tile.{
  AccumulatorExample,
  BuildRoCC,
  MaxHartIdBits,
  OpcodeSet,
  RocketTileParams,
  TileKey,
}
import org.chipsalliance.cde.config.{Config, Parameters, Field}
case object XLen extends Field[Int]

class CEConfig
  extends Config((site, here, _) => {
    case XLen            => 32
    case CacheBlockBytes => site(XLen)
    case TileKey => {
      RocketTileParams(
        core = RocketCoreParams(
          xLen = site(XLen),
          pgLevels = 2,
          useVM = false,
          fpu = None,
          mulDiv = Some(MulDivParams(mulUnroll = 8)),
          useNMI = true,
          clockGate = true,
          mtvecWritable = true,
          mtvecInit = Some(BigInt(0x10000)),
        ),
        btb = None,
        dcache = Some(
          DCacheParams(
            rowBits = here(CacheBlockBytes) * 8,
            nSets = 256,
            nWays = 1,
            nTLBSets = 1,
            nTLBWays = 4,
            nMSHRs = 0,
            blockBytes = here(CacheBlockBytes),
          ),
        ),
        icache = Some(
          ICacheParams(
            rowBits = here(CacheBlockBytes) * 8,
            nSets = 64,
            nWays = 1,
            nTLBSets = 1,
            nTLBWays = 4,
            blockBytes = here(CacheBlockBytes),
          ),
        ),
      )
    }
    case MaxHartIdBits => 8
  })

class WithAccumulatorRoCCExample
  extends Config((_, _, up) => {
    case BuildRoCC => {
      val otherRoccAcc = up(BuildRoCC)
      List { (p: Parameters) =>
        val roccAcc = LazyModule(new AccumulatorExample(OpcodeSet.custom0)(p))
        roccAcc
      } ++ otherRoccAcc
    }
  })

class WithRoCCBridge
  extends Config((_, _, _) => {
    case simpleRoCC.InsertRoCCIO => true
    case BuildRoCC => {
      // val otherRoccAcc = up(BuildRoCC)
      List { (p: Parameters) =>
        val roccBridge = LazyModule(new simpleRoCC.RoCCIOBridge(OpcodeSet.custom0)(p))
        roccBridge
      } // ++ otherRoccAcc
    }
  })

class RV32Config            extends Config(new CEConfig)
class RV32WithRoCCAccConfig extends Config(new WithAccumulatorRoCCExample ++ new RV32Config)
class RV32WithRoCCIOConfig  extends Config(new WithRoCCBridge ++ new RV32Config)
class RV64Config            extends Config((new RV32Config).alterMap(Map((XLen, 64))))
class RV64WithRoCCAccConfig extends Config(new WithAccumulatorRoCCExample ++ new RV64Config)
class RV64WithRoCCIOConfig  extends Config(new WithRoCCBridge ++ new RV64Config)
