import circt.stage.ChiselStage
import java.nio.file.Paths

object GenerateCaravelFlexSnn extends App {
  private val commonArgs = Array("--lowering-options=disallowLocalVariables,disallowPackedArrays")
  private val outputDir = "../verilog/rtl"

  ChiselStage.emitSystemVerilogFile(
    new WishboneMultiLayerSnnFlex(
      layerSizes = Seq(8, 8, 4)
    ) {
      override def desiredName = "WishboneSnn"
    },
    Array("--target-dir", outputDir),
    commonArgs
  )
  GeneratedRtl.stripBlackBoxFooter(Paths.get(outputDir, "WishboneSnn.sv"))
}
