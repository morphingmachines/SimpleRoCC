
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import _root_.circt.stage.ChiselStage
val dut = LazyModule(new ce.sim.SimDUT()(new Config(new ce.RV32WithL2)))
val l2 = dut.uncore.asInstanceOf[ce.HasL2Cache].l2cache
ChiselStage.emitCHIRRTL(l2.module)
val dcache = dut.ce.cetile.dcache
ChiselStage.emitCHIRRTL(dcache.module)
// You can query "HasL1HellaCacheParameters" using "dcache.module._"
import sifive.blocks.inclusivecache._
val p = Parameters.empty
val params = InclusiveCacheParameters(l2.cache, l2.micro, !l2.ctrls.isEmpty, l2.node.edges.in(0), l2.node.edges.out(0))(p)
