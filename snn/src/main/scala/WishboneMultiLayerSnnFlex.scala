import chisel3._

class WishboneMultiLayerSnnFlex(
  layerSizes: Seq[Int],
  weightWidth: Int = 8,
  neuronWidth: Int = 16,
  threshold: Int = 64,
  leak: Int = 1
) extends Module {
  val wb = IO(new wishbone.WishboneIO(addrWidth = 32, dataWidth = 32))
  val io = IO(new Bundle {
    val spikesOut = Output(UInt(layerSizes.last.W))
  })

  private val shell = Module(new WishboneMultiLayerSnnShell(
    layerSizes = layerSizes
  ))
  private val core = Module(new MultiLayerSnnFlex(
    capacityLayerSizes = layerSizes,
    weightWidth = weightWidth,
    neuronWidth = neuronWidth,
    threshold = threshold,
    leak = leak
  ))

  wb <> shell.wb
  io.spikesOut := shell.io.spikesOut

  core.io.start    := shell.core.start
  core.io.spikesIn := shell.core.spikesIn
  shell.core.busy     := core.io.busy
  shell.core.done     := core.io.done
  shell.core.spikesOut := core.io.spikesOut
  shell.core.configNLayers  := core.io.configNLayers
  shell.core.configNInputs  := core.io.configNInputs
  shell.core.configNOutputs := core.io.configNOutputs
  shell.core.sram.csb  := core.io.sram.csb
  shell.core.sram.web  := core.io.sram.web
  shell.core.sram.addr := core.io.sram.addr
  shell.core.sram.din  := core.io.sram.din
  core.io.sram.dout := shell.core.sram.dout
}
