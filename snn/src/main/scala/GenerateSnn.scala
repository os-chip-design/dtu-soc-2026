import circt.stage.ChiselStage
import java.nio.file.Paths

object GenerateSnn extends App {
  private val commonArgs = Array("--lowering-options=disallowLocalVariables,disallowPackedArrays")
  private val outputDir = "verilog/rtl"

  private def emit(gen: => chisel3.RawModule, fileName: String): Unit = {
    ChiselStage.emitSystemVerilogFile(
      gen,
      Array("--target-dir", outputDir),
      commonArgs
    )
    GeneratedRtl.stripBlackBoxFooter(Paths.get(outputDir, fileName))
  }

  emit(new WishboneMultiLayerSnnFlex(
    layerSizes = Seq(8, 8, 4)
  ) {
    override def desiredName = "WishboneMultiLayerSnnFlexTop"
  }, "WishboneMultiLayerSnnFlexTop.sv")
}
